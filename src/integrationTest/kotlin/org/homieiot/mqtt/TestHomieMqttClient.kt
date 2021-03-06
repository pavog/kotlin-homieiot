package org.homieiot.mqtt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.awaitility.Awaitility.await
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.homieiot.Device
import org.homieiot.Property
import org.homieiot.device
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URLClassLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Testcontainers
class TestHomieMqttClient {

    @Rule
    val environmentVariables = EnvironmentVariables()

    @Container
    val mosquitto: GenericContainer<*> = GenericContainer<Nothing>("eclipse-mosquitto:1.5.4").withExposedPorts(1883)

    @Test
    fun `only permits connect to be called once`() {

        val device = device(id = "foo", name = "name") { }
        homieClient(device) {
            assertThatExceptionOfType(IllegalStateException::class.java).isThrownBy { it.connect() }
        }
    }

    @Test
    fun `test publishes ready state`() {
        val device = device(id = "foo", name = "name") { }
        homieClient(device) {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted {
                val publishedMessage = getPublishedMessage("homie/foo/\$state").get(5, TimeUnit.SECONDS)
                assertThat(publishedMessage).isEqualTo("ready")
            }
        }
    }

    @Test
    fun `test publishes config`() {
        val device = device(id = "foo", name = "name") {
            node(id = "node", type = "test") {
                string(id = "foo", name = "test")
            }
        }
        homieClient(device) {
            val nodeMessage = getPublishedMessage("homie/foo/node/\$type").get(5, TimeUnit.SECONDS)
            assertThat(nodeMessage).isEqualTo("test")
            val property = getPublishedMessage("homie/foo/node/\$properties").get(5, TimeUnit.SECONDS)
            assertThat(property).isEqualTo("foo")
            val propertyName = getPublishedMessage("homie/foo/node/foo/\$name").get(5, TimeUnit.SECONDS)
            assertThat(propertyName).isEqualTo("test")

        }
    }

    @Test
    fun `test supports homie base topic`() {
        val device = device(id = "foo", name = "name") { }
        homieClient(device = device, baseTopic = "changed") {
            val publishedMessage = getPublishedMessage("changed/foo/\$name").get(5, TimeUnit.SECONDS)
            assertThat(publishedMessage).isEqualTo("name")
        }
    }

    @Test
    fun `test sends disconnect message before shutdown`() {
        val device = device(id = "foo", name = "name") { }
        homieClient(device) { }
        //Check multiple times until the disconnect value gets published
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            val publishedMessage = getPublishedMessage("homie/foo/\$state").get(5, TimeUnit.SECONDS)
            println(publishedMessage)
            assertThat(publishedMessage).isEqualTo("disconnected")
        }
        Thread.sleep(500) //Wait for the initial messages to publish
    }

    @Test
    fun `test MQTT connection`() {
        val device = device(id = "foo", name = "name") {
        }
        var run = false
        homieClient(device) {
            run = true
        }
        assertThat(run).isEqualTo(true)
    }

    @Test
    fun `test MQTT connection From environment`() {

        (ClassLoader.getSystemClassLoader() as URLClassLoader).urLs.forEach { println(it.file) }


        assertThat(mosquitto.isRunning())
        val device = device(id = "foo", name = "name") { }

        environmentVariables.set("MQTT_SERVER", serverURI())
        environmentVariables.set("MQTT_CLIENT_ID", clientID())
        val client = HomieMqttClient.fromEnv(device = device)

        val connectFuture = client.connect()
        connectFuture.get(5, TimeUnit.SECONDS)
        assertThat(connectFuture.isDone).isEqualTo(true)
    }

    @Test
    fun `test homie publish update`() {
        var property: Property<String>? = null
        val device = device(id = "foo", name = "name") {
            node(id = "node", type = "type", name = "name") {
                property = string(id = "bar")
            }
        }

        homieClient(device) {
            val message = "foo"
            property!!.update(message)

            val publishedMessage = getPublishedMessage("homie/foo/node/bar").get(5, TimeUnit.SECONDS)
            assertThat(publishedMessage).isEqualTo(message)
        }
    }

    @Test
    fun `test MQTT publish update`() {
        var propertyUpdate: String? = null
        val device = device(id = "foo", name = "name") {
            node(id = "node", type = "type", name = "name") {
                string(id = "bar") {
                    subscribe { propertyUpdate = it.update }
                }
            }
        }

        val update = "foo"

        homieClient(device) {
            assertThat(getPublishedMessage("homie/foo/node/bar/\$settable").get(5, TimeUnit.SECONDS)).isEqualTo("true")
            publishMessage("homie/foo/node/bar/set", update)
            await().atMost(5, TimeUnit.SECONDS).untilAsserted {
                assertThat(propertyUpdate).isEqualTo(update)
            }
        }
    }

    private fun clientID() = java.util.UUID.randomUUID().toString()
    private fun serverURI() = "tcp://${mosquitto.getContainerIpAddress()}:${mosquitto.getMappedPort(1883)}"

    private fun homieClient(
        device: Device,
        baseTopic: String = "homie",
        init: (client: HomieMqttClient) -> Unit
    ) {
        assertThat(mosquitto.isRunning())
        val client = HomieMqttClient(
            serverURI = serverURI(),
            clientID = clientID(),
            homieRoot = baseTopic,
            device = device
        )
        val connectFuture = client.connect()
        connectFuture.get(5, TimeUnit.SECONDS)
        assertThat(connectFuture.isDone).isEqualTo(true)
        init(client)
        client.disconnect()
    }

    private fun client() = MqttClient(serverURI(), clientID())

    private fun publishMessage(topic: String, message: String) {
        client().apply {
            connect()
            publish(topic, MqttMessage(message.toByteArray()))
            disconnect(5000)
            close()
        }
    }

    private fun getPublishedMessage(topic: String): Future<String> {
        val futureMessage = CompletableFuture<String>()

        client().apply {
            connect()
            subscribe(topic) { _, message ->
                futureMessage.complete(String(message.payload))
            }
        }
        return futureMessage
    }
}