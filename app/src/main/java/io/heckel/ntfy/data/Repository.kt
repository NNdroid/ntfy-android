package io.heckel.ntfy.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.heckel.ntfy.Notification
import io.heckel.ntfy.NotificationListener
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val READ_TIMEOUT = 60_000 // Keep alive every 30s assumed

class Repository {
    private val topics: MutableLiveData<List<Topic>> = MutableLiveData(mutableListOf())
    private val jobs = mutableMapOf<Long, Job>()
    private val gson = GsonBuilder().create()
    private var notificationListener: NotificationListener? = null;

    fun add(topic: Topic, scope: CoroutineScope) {
        val currentList = topics.value
        if (currentList == null) {
            topics.postValue(listOf(topic))
        } else {
            val updatedList = currentList.toMutableList()
            updatedList.add(0, topic)
            topics.postValue(updatedList)
        }
        jobs[topic.id] = subscribeTopic(topic, scope)
    }

    fun update(topic: Topic) {
        val currentList = topics.value
        if (currentList == null) {
            topics.postValue(listOf(topic))
        } else {
            val index = currentList.indexOfFirst { it.id == topic.id } // Find index by Topic ID
            if (index == -1) {
                return // TODO race?
            } else {
                val updatedList = currentList.toMutableList()
                updatedList[index] = topic
                println("PHIL updated list:")
                println(updatedList)
                topics.postValue(updatedList)
            }
        }
    }

    fun remove(topic: Topic) {
        val currentList = topics.value
        if (currentList != null) {
            val updatedList = currentList.toMutableList()
            updatedList.remove(topic)
            topics.postValue(updatedList)
        }
        jobs.remove(topic.id)?.cancel() // Cancel coroutine and remove
    }

    fun get(id: Long): Topic? {
        topics.value?.let { topics ->
            return topics.firstOrNull{ it.id == id}
        }
        return null
    }

    fun list(): LiveData<List<Topic>> {
        return topics
    }

    fun setNotificationListener(listener: NotificationListener) {
        notificationListener = listener
    }

    private fun subscribeTopic(topic: Topic, scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                openConnection(this, topic)
                delay(5000) // TODO exponential back-off
            }
        }
    }

    private fun openConnection(scope: CoroutineScope, topic: Topic) {
        val url = "${topic.baseUrl}/${topic.name}/json"
        println("Connecting to $url ...")
        val conn = (URL(url).openConnection() as HttpURLConnection).also {
            it.doInput = true
            it.readTimeout = READ_TIMEOUT
        }
        // TODO ugly
        val currentTopic = get(topic.id)
        if (currentTopic != null) {
            update(currentTopic.copy(status = Status.SUBSCRIBED))
        }
        try {
            val input = conn.inputStream.bufferedReader()
            while (scope.isActive) {
                val line = input.readLine() ?: break // Break if EOF is reached, i.e. readLine is null
                if (!scope.isActive) {
                    break // Break if scope is not active anymore; readLine blocks for a while, so we want to be sure
                }
                try {
                    val json = gson.fromJson(line, JsonObject::class.java) ?: break // Break on unexpected line
                    if (!json.isJsonNull && json.has("message")) {
                        val message = json.get("message").asString
                        notificationListener?.let { it(Notification(topic, message)) }

                        // TODO ugly
                        val currentTopic = get(topic.id)
                        if (currentTopic != null) {
                            update(currentTopic.copy(messages = currentTopic.messages+1))
                        }
                    }
                } catch (e: JsonSyntaxException) {
                    break // Break on unexpected line
                }
            }
        } catch (e: IOException) {
            println("Connection error: " + e.message)
        } finally {
            conn.disconnect()
        }
        val currentTopic2 = get(topic.id)
        if (currentTopic2 != null) {
            update(currentTopic2.copy(status = Status.CONNECTING))
        }
        println("Connection terminated: $url")
    }

    companion object {
        private var instance: Repository? = null

        fun getInstance(): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository()
                instance = newInstance
                newInstance
            }
        }
    }
}
