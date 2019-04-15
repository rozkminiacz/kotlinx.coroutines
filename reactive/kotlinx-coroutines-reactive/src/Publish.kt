/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.reactive

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.*
import kotlinx.coroutines.sync.*
import org.reactivestreams.*
import kotlin.coroutines.*

/**
 * Creates cold reactive [Publisher] that runs a given [block] in a coroutine.
 * Every time the returned publisher is subscribed, it starts a new coroutine.
 * Coroutine emits items with `send`. Unsubscribing cancels running coroutine.
 *
 * Invocations of `send` are suspended appropriately when subscribers apply back-pressure and to ensure that
 * `onNext` is not invoked concurrently.
 *
 * | **Coroutine action**                         | **Signal to subscriber**
 * | -------------------------------------------- | ------------------------
 * | `send`                                       | `onNext`
 * | Normal completion or `close` without cause   | `onComplete`
 * | Failure with exception or `close` with cause | `onError`
 *
 * Coroutine context is inherited from a [CoroutineScope], additional context elements can be specified with [context] argument.
 * If the context does not have any dispatcher nor any other [ContinuationInterceptor], then [Dispatchers.Default] is used.
 * The parent job is inherited from a [CoroutineScope] as well, but it can also be overridden
 * with corresponding [coroutineContext] element.
 *
 * **Note: This is an experimental api.** Behaviour of publishers that work as children in a parent scope with respect
 *        to cancellation and error handling may change in the future.
 *
 * @param context context of the coroutine.
 * @param block the coroutine code.
 */
@ExperimentalCoroutinesApi
public fun <T> CoroutineScope.publish(
    context: CoroutineContext = EmptyCoroutineContext,
    @BuilderInference block: suspend ProducerScope<T>.() -> Unit
): Publisher<T> = Publisher { subscriber ->
    // specification requires NPE on null subscriber
    if (subscriber == null) throw NullPointerException("Subscriber cannot be null")
    val newContext = newCoroutineContext(context)
    val coroutine = PublisherCoroutine(newContext, subscriber)
    subscriber.onSubscribe(coroutine) // do it first (before starting coroutine), to avoid unnecessary suspensions
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
}

private const val CLOSED = -1L    // closed, but have not signalled onCompleted/onError yet
private const val SIGNALLED = -2L  // already signalled subscriber onCompleted/onError

@Suppress("CONFLICTING_JVM_DECLARATIONS", "RETURN_TYPE_MISMATCH_ON_INHERITANCE")
private class PublisherCoroutine<in T>(
    parentContext: CoroutineContext,
    private val subscriber: Subscriber<T>
) : AbstractCoroutine<Unit>(parentContext, true), ProducerScope<T>, Subscription {
    override val channel: SendChannel<T> get() = this

    // Mutex is locked when either nRequested == 0 or while subscriber.onXXX is being invoked
    private val mutex = Mutex(locked = true)

    private val _nRequested = atomic(0L) // < 0 when closed (CLOSED or SIGNALLED)

    @Volatile
    private var cancelled = false // true when Subscription.cancel() is invoked

    override val isClosedForSend: Boolean get() = isCompleted
    override fun close(cause: Throwable?): Boolean = cancelCoroutine(cause)
    override fun invokeOnClose(handler: (Throwable?) -> Unit) =
        throw UnsupportedOperationException("PublisherCoroutine doesn't support invokeOnClose")

    override fun offer(element: T): Boolean {
        if (!mutex.tryLock()) return false
        doLockedNext(element)
        return true
    }

    public override suspend fun send(element: T) {
        // fast-path -- try send without suspension
        if (offer(element)) return
        // slow-path does suspend
        return sendSuspend(element)
    }

    private suspend fun sendSuspend(element: T) {
        mutex.lock()
        doLockedNext(element)
    }

    override val onSend: SelectClause2<T, SendChannel<T>> get() = SelectClause2Impl(
        objForSelect = this.mutex,
            regFunc = this.mutex.onLock.regFunc,
            processResFunc = PublisherCoroutine<*>::onSendProcessResFunction as ProcessResultFunction
    )

    private fun onSendProcessResFunction(element: T, selectResult: Any?): Any? {
        this.mutex.onLock.processResFunc(this.mutex, element, selectResult)
        doLockedNext(element)
        return this
    }

    /*
     * This code is not trivial because of the two properties:
     * 1. It ensures conformance to the reactive specification that mandates that onXXX invocations should not
     *    be concurrent. It uses Mutex to protect all onXXX invocation and ensure conformance even when multiple
     *    coroutines are invoking `send` function.
     * 2. Normally, `onComplete/onError` notification is sent only when coroutine and all its children are complete.
     *    However, nothing prevents `publish` coroutine from leaking reference to it send channel to some
     *    globally-scoped coroutine that is invoking `send` outside of this context. Without extra precaution this may
     *    lead to `onNext` that is concurrent with `onComplete/onError`, so that is why signalling for
     *    `onComplete/onError` is also done under the same mutex.
     */

    // assert: mutex.isLocked()
    private fun doLockedNext(elem: T) {
        // check if already closed for send, note, that isActive become false as soon as cancel() is invoked,
        // because the job is cancelled, so this check also ensure conformance to the reactive specification's
        // requirement that after cancellation requested we don't call onXXX
        if (!isActive) {
            unlockAndCheckCompleted()
            throw getCancellationException()
        }
        // notify subscriber
        try {
            subscriber.onNext(elem)
        } catch (e: Throwable) {
            // If onNext fails with exception, then we cancel coroutine (with this exception) and then rethrow it
            // to abort the corresponding send/offer invocation. From the standpoint of coroutines machinery,
            // this failure is essentially equivalent to a failure of a child coroutine.
            cancelCoroutine(e)
            unlockAndCheckCompleted()
            throw e
        }
        // now update nRequested
        while (true) { // lock-free loop on nRequested
            val cur = _nRequested.value
            if (cur < 0) break // closed from inside onNext => unlock
            if (cur == Long.MAX_VALUE) break // no back-pressure => unlock
            val upd = cur - 1
            if (_nRequested.compareAndSet(cur, upd)) {
                if (upd == 0L) {
                    // return to keep locked due to back-pressure
                    return
                }
                break // unlock if upd > 0
            }
        }
        unlockAndCheckCompleted()
    }

    private fun unlockAndCheckCompleted() {
       /*
        * There is no sense to check completion before doing `unlock`, because completion might
        * happen after this check and before `unlock` (see `signalCompleted` that does not do anything
        * if it fails to acquire the lock that we are still holding).
        * We have to recheck `isCompleted` after `unlock` anyway.
        */
        mutex.unlock()
        // check isCompleted and and try to regain lock to signal completion
        if (isCompleted && mutex.tryLock()) {
            doLockedSignalCompleted(completionCause, completionCauseHandled)
        }
    }

    // assert: mutex.isLocked() & isCompleted
    private fun doLockedSignalCompleted(cause: Throwable?, handled: Boolean) {
        try {
            if (_nRequested.value >= CLOSED) {
                _nRequested.value = SIGNALLED // we'll signal onError/onCompleted (that the final state -- no CAS needed)
                // Specification requires that after cancellation requested we don't call onXXX
                if (cancelled) {
                    // If the parent had failed to handle our exception, then we must not lose this exception
                    if (cause != null && !handled) handleCoroutineException(context, cause)
                } else {
                    try {
                        if (cause != null && cause !is CancellationException) {
                            subscriber.onError(cause)
                        }
                        else {
                            subscriber.onComplete()
                        }
                    } catch (e: Throwable) {
                        handleCoroutineException(context, e)
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    override fun request(n: Long) {
        if (n <= 0) {
            // Specification requires IAE for n <= 0
            cancelCoroutine(IllegalArgumentException("non-positive subscription request $n"))
            return
        }
        while (true) { // lock-free loop for nRequested
            val cur = _nRequested.value
            if (cur < 0) return // already closed for send, ignore requests
            var upd = cur + n
            if (upd < 0 || n == Long.MAX_VALUE)
                upd = Long.MAX_VALUE
            if (cur == upd) return // nothing to do
            if (_nRequested.compareAndSet(cur, upd)) {
                // unlock the mutex when we don't have back-pressure anymore
                if (cur == 0L) {
                    unlockAndCheckCompleted()
                }
                return
            }
        }
    }

    // assert: isCompleted
    private fun signalCompleted(cause: Throwable?, handled: Boolean) {
        while (true) { // lock-free loop for nRequested
            val cur = _nRequested.value
            if (cur == SIGNALLED) return // some other thread holding lock already signalled cancellation/completion
            check(cur >= 0) // no other thread could have marked it as CLOSED, because onCompleted[Exceptionally] is invoked once
            if (!_nRequested.compareAndSet(cur, CLOSED)) continue // retry on failed CAS
            // Ok -- marked as CLOSED, now can unlock the mutex if it was locked due to backpressure
            if (cur == 0L) {
                doLockedSignalCompleted(cause, handled)
            } else {
                // otherwise mutex was either not locked or locked in concurrent onNext... try lock it to signal completion
                if (mutex.tryLock()) doLockedSignalCompleted(cause, handled)
                // Note: if failed `tryLock`, then `doLockedNext` will signal after performing `unlock`
            }
            return // done anyway
        }
    }

    override fun onCompleted(value: Unit) {
        signalCompleted(null, false)
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) {
        signalCompleted(cause, handled)
    }

    override fun cancel() {
        // Specification requires that after cancellation publisher stops signalling
        // This flag distinguishes subscription cancellation request from the job crash
        cancelled = true
        super.cancel(null)
    }
}