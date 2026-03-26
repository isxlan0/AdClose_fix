package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.repository.CloudRuleOperationResult
import com.close.hook.ads.data.repository.CloudRuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CloudRuleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val repository = CloudRuleRepository.getInstance(app)

    private val searchQuery = MutableStateFlow("")
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _workingIds = MutableStateFlow<Set<Long>>(emptySet())

    val messages = _messages.asSharedFlow()
    val workingIds: StateFlow<Set<Long>> = _workingIds.asStateFlow()

    val sources = searchQuery
        .debounce(300L)
        .flatMapLatest { repository.observeSourceSummaries(it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureDefaultSourceInitialized()
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query.trim()
    }

    fun saveSource(
        sourceId: Long?,
        url: String,
        enabled: Boolean,
        autoUpdateEnabled: Boolean,
        updateIntervalHoursText: String
    ) {
        val intervalHours = updateIntervalHoursText.trim().toLongOrNull() ?: Long.MIN_VALUE
        viewModelScope.launch(Dispatchers.IO) {
            sourceId?.let { markWorking(it, true) }
            try {
                val result = if (sourceId == null) {
                    repository.addSource(url, enabled, autoUpdateEnabled, intervalHours)
                } else {
                    repository.updateSource(sourceId, url, enabled, autoUpdateEnabled, intervalHours)
                }
                val message = if (result.success) {
                    app.getString(
                        if (sourceId == null) R.string.cloud_rule_source_added
                        else R.string.cloud_rule_source_updated
                    )
                } else {
                    resolveMessage(result)
                }
                _messages.tryEmit(message)
            } finally {
                sourceId?.let { markWorking(it, false) }
            }
        }
    }

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        performSourceOperation(sourceId, showSuccess = false) {
            repository.setSourceEnabled(sourceId, enabled)
        }
    }

    fun setAutoUpdateEnabled(sourceId: Long, enabled: Boolean) {
        performSourceOperation(sourceId, showSuccess = false) {
            repository.setAutoUpdateEnabled(sourceId, enabled)
        }
    }

    fun deleteSource(sourceId: Long) {
        performSourceOperation(
            sourceId = sourceId,
            showSuccess = true,
            successMessage = app.getString(R.string.cloud_rule_source_deleted)
        ) {
            repository.deleteSource(sourceId)
        }
    }

    fun syncSource(sourceId: Long) {
        performSourceOperation(
            sourceId = sourceId,
            showSuccess = true,
            successMessage = app.getString(R.string.cloud_rule_sync_success),
            failureMessage = { result -> resolveMessage(result, syncFailure = true) }
        ) {
            repository.syncSourceNow(sourceId)
        }
    }

    private fun performSourceOperation(
        sourceId: Long,
        showSuccess: Boolean,
        successMessage: String? = null,
        failureMessage: ((CloudRuleOperationResult) -> String)? = null,
        block: suspend () -> CloudRuleOperationResult
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            markWorking(sourceId, true)
            try {
                val result = block()
                when {
                    result.success && showSuccess && successMessage != null -> _messages.tryEmit(successMessage)
                    !result.success -> _messages.tryEmit(failureMessage?.invoke(result) ?: resolveMessage(result))
                }
            } finally {
                markWorking(sourceId, false)
            }
        }
    }

    private fun markWorking(sourceId: Long, working: Boolean) {
        _workingIds.update { ids ->
            if (working) ids + sourceId else ids - sourceId
        }
    }

    private fun resolveMessage(
        result: CloudRuleOperationResult,
        syncFailure: Boolean = false
    ): String {
        return when (result.message) {
            "invalid_url" -> app.getString(R.string.cloud_rule_invalid_url)
            "invalid_interval" -> app.getString(R.string.cloud_rule_invalid_interval)
            "duplicate_url" -> app.getString(R.string.cloud_rule_duplicate_source)
            "not_found", null -> app.getString(R.string.cloud_rule_source_not_found)
            else -> {
                if (syncFailure) {
                    app.getString(R.string.cloud_rule_sync_failed_with_reason, result.message)
                } else {
                    result.message ?: app.getString(R.string.cloud_rule_source_not_found)
                }
            }
        }
    }
}
