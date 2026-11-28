package com.familyhub.app.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the phone is online and how many records still wait to reach the server. Firestore keeps changes made
 * offline on the phone and sends them when the connection is back; this only shows that to the person.
 */
object SyncStatus {
    data class State(val online: Boolean = true, val pending: Int = 0) {
        val text get() = SyncMath.text(online, pending)
        val level get() = SyncMath.level(online, pending)
    }
    private val pendingByList = HashMap<String, Int>()
    private val state = MutableStateFlow(State())
    val flow: StateFlow<State> = state

    private var demo = false

    @Synchronized fun reportPending(listener: String, count: Int) {
        if (demo) return
        if (count == 0) pendingByList.remove(listener) else pendingByList[listener] = count
        state.value = state.value.copy(pending = pendingByList.values.sum())
    }

    fun setOnline(on: Boolean) { if (!demo && state.value.online != on) state.value = state.value.copy(online = on) }

    /** Test screens only. */
    fun showForDemo(online: Boolean, pending: Int) { demo = true; state.value = State(online, pending) }

    fun watch(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        setOnline(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = setOnline(true)
                override fun onLost(network: Network) = setOnline(false)
            })
        }
    }
}
