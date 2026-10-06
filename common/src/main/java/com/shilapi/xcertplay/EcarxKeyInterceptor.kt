package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Claims the steering-wheel keys of a Geely ECARX head unit.
 *
 * A Geely head unit does not broadcast `ACTION_MEDIA_BUTTON` for its wheel. The keys go to whoever
 * holds an interception on the head unit's own input service, and its callback reports the vendor
 * key codes in [com.shilapi.xcertplay.airplay.CarPlayMediaButton]. Without that interception DiPlay
 * sees no steering-wheel events at all, which is why the wheel looks dead on these cars.
 *
 * The vendor API is reached reflectively because it exists only on ECARX builds: on any other head
 * unit [isSupported] is false and every method here is a no-op.
 */
internal class EcarxKeyInterceptor(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val pressedKeys = Collections.synchronizedSet(mutableSetOf<Int>())

    private var input: Any? = null
    private var callback: Any? = null

    /** The key codes the head unit agreed to hand over; empty while no interception is held. */
    var accepted: IntArray = IntArray(0)
        private set

    val isActive: Boolean get() = callback != null

    /**
     * Asks the head unit to hand over [keyCodes], reporting each press to [onPressed]. Returns the
     * subset the head unit accepted, or null when the vendor service is absent or refused.
     *
     * [onPressed] returning true consumes the key, so the car does not also act on it; returning
     * false leaves the press to the head unit's own handling.
     */
    fun start(keyCodes: IntArray, onPressed: (Int) -> Boolean): IntArray? {
        stop()
        val service = createInput() ?: return null
        return try {
            val callbackType = Class.forName(CALLBACK_CLASS)
            val proxy = Proxy.newProxyInstance(
                callbackType.classLoader ?: appContext.classLoader,
                arrayOf(callbackType),
                handler(onPressed),
            )
            val request = service.javaClass.getMethod(
                "requestKeysInterception",
                IntArray::class.java,
                callbackType,
            )
            val granted = request.invoke(service, keyCodes, proxy) as? IntArray ?: return null
            input = service
            callback = proxy
            accepted = granted
            granted
        } catch (error: Exception) {
            Log.w(TAG, "requestKeysInterception failed", error)
            null
        }
    }

    fun stop() {
        val service = input
        val held = callback
        input = null
        callback = null
        accepted = IntArray(0)
        pressedKeys.clear()
        if (service == null || held == null) return
        try {
            service.javaClass
                .getMethod("abandonKeysInterception", held.javaClass.interfaces.first())
                .invoke(service, held)
        } catch (error: Exception) {
            Log.w(TAG, "abandonKeysInterception failed", error)
        }
    }

    private fun createInput(): Any? = try {
        Class.forName(INPUT_CLASS).getMethod("create", Context::class.java).invoke(null, appContext)
    } catch (error: Exception) {
        Log.w(TAG, "the ECARX input service is unavailable on this build", error)
        null
    }

    private fun handler(onPressed: (Int) -> Boolean) = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "onKeyPressed" -> {
                val keyCode = args?.firstOrNull() as? Int ?: return@InvocationHandler false
                if (!onPressed(keyCode)) return@InvocationHandler false
                pressedKeys.add(keyCode)
                true
            }
            "onKeyReleased" -> {
                val keyCode = args?.firstOrNull() as? Int ?: return@InvocationHandler false
                pressedKeys.remove(keyCode)
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "CarPlayKeyCallback"
            else -> defaultFor(method.returnType)
        }
    }

    /** A dynamic proxy must answer every method the interface declares, not only the ones we use. */
    private fun defaultFor(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    companion object {
        private const val TAG = "DiPlay-EcarxKeys"
        private const val INPUT_CLASS = "com.ecarx.xui.adaptapi.input.Input"
        private const val CALLBACK_CLASS = "com.ecarx.xui.adaptapi.input.IKeyCallback"

        @Volatile private var supported: Boolean? = null

        /** Whether this head unit exposes the ECARX input service. The answer is cached. */
        fun isSupported(): Boolean {
            supported?.let { return it }
            val detected = detectSupport()
            supported = detected
            return detected
        }

        private fun detectSupport(): Boolean = try {
            Class.forName(INPUT_CLASS)
            Class.forName(CALLBACK_CLASS)
            true
        } catch (error: ClassNotFoundException) {
            Log.i(TAG, "no ECARX key interception on this head unit")
            false
        }
    }
}
