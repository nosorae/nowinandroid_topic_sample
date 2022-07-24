/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.feature.topic

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.core.domain.repository.NewsRepository
import com.google.samples.apps.nowinandroid.core.domain.repository.TopicsRepository
import com.google.samples.apps.nowinandroid.core.model.data.FollowableTopic
import com.google.samples.apps.nowinandroid.core.model.data.NewsResource
import com.google.samples.apps.nowinandroid.core.model.data.Topic
import com.google.samples.apps.nowinandroid.core.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class TopicViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val topicsRepository: TopicsRepository,
    newsRepository: NewsRepository
) : ViewModel() {

    private val topicId: Int? = savedStateHandle[TopicDestinationsArgs.TOPIC_ID_ARG]

    // Observe the followed topics, as they could change over time.
    private val followedTopicIdsStream: StateFlow<Result<Set<Int>>> =
        topicsRepository.getFollowedTopicIdsStream()
            .map { Result.Success(it) }
            .catch {
                Result.Error(it)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = Result.Loading
            )

    // Observe topic information
    private val topic: StateFlow<Result<Topic>> = flow {
        try {
            if (topicId == null) {
                emit(Result.Error(IllegalStateException("Topic ID was null.")))
                return@flow
            }
            emit(Result.Success(topicsRepository.getTopic(topicId)))
        } catch (e: Exception) {
            Log.e("TopicViewModel", e.message ?: "Error loading topic")
            emit(Result.Error(e))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Result.Loading
    )

    // Observe the News for this topic
    private val newsStream: StateFlow<Result<List<NewsResource>>> =
        newsRepository.getNewsResourcesStream(setOf(topicId ?: 0)) // null topic handled by `topic`
            .map {
                Result.Success(it)
            }
            .catch {
                Result.Error(it)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = Result.Loading
            )

    val uiState: StateFlow<TopicScreenUiState> =
        combine(
            followedTopicIdsStream,
            topic,
            newsStream
        ) { followedTopicsResult, topicResult, newsResult ->
            val topic: TopicUiState =
                if (topicResult is Result.Success && followedTopicsResult is Result.Success) {
                    val followed = followedTopicsResult.data.contains(topicId)
                    TopicUiState.Success(
                        followableTopic = FollowableTopic(
                            topic = topicResult.data,
                            isFollowed = followed
                        )
                    )
                } else if (
                    topicResult is Result.Loading || followedTopicsResult is Result.Loading
                ) {
                    TopicUiState.Loading
                } else {
                    TopicUiState.Error
                }

            val news: NewsUiState = when (newsResult) {
                is Result.Success -> NewsUiState.Success(newsResult.data)
                is Result.Loading -> NewsUiState.Loading
                is Result.Error -> NewsUiState.Error
            }

            TopicScreenUiState(topic, news)
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TopicScreenUiState(TopicUiState.Loading, NewsUiState.Loading)
            )

    fun followTopicToggle(followed: Boolean) {
        viewModelScope.launch {
            topicId?.let {
                topicsRepository.toggleFollowedTopicId(it, followed)
            }
        }
    }
}

sealed interface TopicUiState {
    data class Success(val followableTopic: FollowableTopic) : TopicUiState
    object Error : TopicUiState
    object Loading : TopicUiState
}

sealed interface NewsUiState {
    data class Success(val news: List<NewsResource>) : NewsUiState
    object Error : NewsUiState
    object Loading : NewsUiState
}

data class TopicScreenUiState(
    val topicState: TopicUiState,
    val newsState: NewsUiState
)
