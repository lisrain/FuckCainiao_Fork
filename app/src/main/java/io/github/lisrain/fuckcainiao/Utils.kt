package io.github.lisrain.fuckcainiao

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

fun Any.dump(): String {
    val sb = StringBuilder()
    this.javaClass.fields.forEach {
        sb.append("${it.name}: ${it.get(this)}\n")
    }
    return sb.toString()
}

fun Class<*>.findMethod(condition: (Method) -> Boolean): Method? {
    var clazz: Class<*>? = this
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredMethods.firstOrNull(condition)?.let { return it }
        clazz = clazz.superclass
    }
    return null
}

fun Class<*>.findAllMethods(condition: (Method) -> Boolean): List<Method> {
    val result = mutableListOf<Method>()
    var clazz: Class<*>? = this
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredMethods.forEach { method ->
            if (condition(method) && result.none {
                    it.name == method.name && it.parameterTypes.contentEquals(method.parameterTypes)
                }
            ) {
                result.add(method)
            }
        }
        clazz = clazz.superclass
    }
    return result
}

fun Class<*>.findField(condition: (Field) -> Boolean): Field? {
    var clazz: Class<*>? = this
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredFields.firstOrNull(condition)?.let { return it }
        clazz = clazz.superclass
    }
    return null
}

fun <T> Any.invokeMethodAutoAs(name: String, vararg args: Any?): T? {
    val method = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
        ?: javaClass.findMethod { it.name == name && it.parameterCount == args.size }
        ?: return null
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return try {
        method.invoke(this, *args) as T
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

fun Any.invokeMethod(name: String, vararg args: Any?): Any? =
    invokeMethodAutoAs<Any>(name, *args)

fun Any.getObjectOrNull(name: String): Any? {
    val field = javaClass.findField { it.name == name } ?: return null
    field.isAccessible = true
    return field.get(this)
}

fun <T> Any.getObjectAs(name: String): T = getObjectOrNull(name) as T

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
