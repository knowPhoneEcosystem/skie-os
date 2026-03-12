package com.skiephone.app.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SkiePolicyState {
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger

    fun notifyUpdated() {
        _refreshTrigger.value++
    }
}