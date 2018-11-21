package org.homieiot.device

import org.homieiot.mqtt.HierarchicalHomiePublisher
import org.homieiot.mqtt.HomiePublisher

interface HomieProperty<T> : org.homieiot.device.HomieUnit {
    val id: String
    val name: String?
    val settable: Boolean
    val retained: Boolean
    val unit: String?
    val datatype: String?
    val format: String?
    val topicSegments: List<String>

    fun update(t: T)
    fun subscribe(update: (PropertyUpdate<T>) -> Unit): HomieProperty<T>
}


data class PropertyUpdate<T>(val property: HomieProperty<T>, val update: T)


abstract class BaseHomieProperty<T>(final override val id: String,
                                    final override val name: String?,
                                    parentPublisher: HomiePublisher,
                                    final override val retained: Boolean,
                                    final override val unit: String?,
                                    final override val datatype: String,
                                    final override val format: String?) : HomieProperty<T>, org.homieiot.device.HomieUnit {

    private val publisher = HierarchicalHomiePublisher(parentPublisher, id)

    private var observer: ((PropertyUpdate<T>) -> Unit)? = null

    private var lastValue: T? = null

    override val settable: Boolean
        get() = observer != null

    override val topicSegments = publisher.topic()


    override fun update(t: T) {
        if (t != lastValue) {
            publisher.publishMessage(payload = valueToString(t))
            lastValue = t
        }
    }

    protected open fun valueToString(value: T): String {
        return value.toString()
    }

    internal fun mqttReceived(update: String) {
        observer?.invoke(propertyUpdateFromString(update))
    }

    abstract fun propertyUpdateFromString(update: String): PropertyUpdate<T>



    override fun publishConfig() {
        name?.let { publisher.publishMessage("\$name", payload = it) }
        publishSettable()
        publisher.publishMessage("\$retained", payload = retained.toString())
        unit?.let { publisher.publishMessage("\$unit", payload = it) }
        publisher.publishMessage("\$datatype", payload = datatype)
        format?.let { publisher.publishMessage("\$format", payload = it) }
    }

    private fun publishSettable() {
        publisher.publishMessage("\$settable", payload = settable.toString())
    }

    override fun subscribe(update: (PropertyUpdate<T>) -> Unit): HomieProperty<T> {
        this.observer = update
        publishSettable()
        return this
    }

}


class StringProperty(id: String,
                     name: String? = null,
                     parentPublisher: HomiePublisher,
                     retained: Boolean = true,
                     unit: String? = null) : BaseHomieProperty<String>(
        id = id,
        name = name,
        unit = unit,
        retained = retained,
        datatype = "string",
        parentPublisher = parentPublisher,
        format = null) {

    override fun propertyUpdateFromString(update: String): PropertyUpdate<String> = PropertyUpdate(this, update)
}


abstract class AbstractNumberProperty<T : Comparable<T>>(id: String,
                                                         name: String?,
                                                         parentPublisher: HomiePublisher,
                                                         retained: Boolean = true,
                                                         datatype: String,
                                                         unit: String? = null,
                                                         private val range: ClosedRange<T>?) : BaseHomieProperty<T>(
        id = id,
        name = name,
        parentPublisher = parentPublisher,
        retained = retained,
        unit = unit,
        datatype = datatype,
        format = range?.let { "${it.start}:${it.endInclusive}" }) {

    override fun update(t: T) {
        range?.containsOrThrow(t)
        super.update(t)
    }
}


class NumberProperty(id: String,
                     name: String?,
                     parentPublisher: HomiePublisher,
                     retained: Boolean = true,
                     unit: String? = null,
                     range: LongRange?) : AbstractNumberProperty<Long>(
        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        datatype = "integer",
        range = range) {

    override fun propertyUpdateFromString(update: String): PropertyUpdate<Long> = PropertyUpdate(this, update.toLong())
}


class FloatProperty(id: String,
                    name: String?,
                    parentPublisher: HomiePublisher,
                    retained: Boolean = true,
                    unit: String? = null,
                    range: ClosedRange<Double>?) : AbstractNumberProperty<Double>(

        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        datatype = "float",
        range = range) {

    override fun propertyUpdateFromString(update: String): PropertyUpdate<Double> = PropertyUpdate(this, update.toDouble())
}


class EnumProperty<E : Enum<E>>(id: String,
                                name: String?,
                                parentPublisher: HomiePublisher,
                                retained: Boolean = true,
                                unit: String? = null,
                                enumValues: List<String>,
                                private val enumMap: Map<String, E>
) : BaseHomieProperty<E>(
        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        datatype = "enum",
        format = enumValues.joinToString(",")
) {

    override fun propertyUpdateFromString(update: String): PropertyUpdate<E> {
        return PropertyUpdate(this, enumMap.getValue(update))
    }
}


class BoolProperty(id: String,
                   name: String?,
                   parentPublisher: HomiePublisher,
                   retained: Boolean = true,
                   unit: String? = null) : BaseHomieProperty<Boolean>(
        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        datatype = "boolean",
        format = null) {

    override fun propertyUpdateFromString(update: String): PropertyUpdate<Boolean> = PropertyUpdate(this, update.toBoolean())
}

abstract class AbstractColorProperty<T>(id: String,
                                        name: String?,
                                        parentPublisher: HomiePublisher,
                                        retained: Boolean = true,
                                        colorType: String,
                                        unit: String? = null) : BaseHomieProperty<T>(
        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        datatype = "color",
        format = colorType) {

    protected fun parseColorString(string: String): Triple<Int, Int, Int> {
        val (first, second, third) = string.split(',').map { it.toInt() }
        return Triple(first, second, third)
    }


}



class HSVColorProperty(id: String,
                       name: String?,
                       parentPublisher: HomiePublisher,
                       retained: Boolean = true,
                       unit: String? = null) : AbstractColorProperty<HSV>(
        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        colorType = "hsv") {

    override fun propertyUpdateFromString(update: String): PropertyUpdate<HSV> = PropertyUpdate(this, HSV(parseColorString(update)))


    override fun valueToString(hsv: HSV): String = "${hsv.hue},${hsv.saturation},${hsv.value}"

}

class RGBColorProperty(id: String,
                       name: String?,
                       parentPublisher: HomiePublisher,
                       retained: Boolean = true,
                       unit: String? = null) : AbstractColorProperty<RGB>(
        id = id,
        name = name,
        retained = retained,
        unit = unit,
        parentPublisher = parentPublisher,
        colorType = "rgb") {

    override fun valueToString(rgb: RGB): String = "${rgb.red},${rgb.green},${rgb.blue}"

    override fun propertyUpdateFromString(update: String): PropertyUpdate<RGB> = PropertyUpdate(this, RGB(parseColorString(update)))
}

