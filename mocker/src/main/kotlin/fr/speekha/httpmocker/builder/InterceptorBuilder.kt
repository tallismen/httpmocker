/*
 * Copyright 2019 David Blanc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.speekha.httpmocker.builder

import fr.speekha.httpmocker.MockResponseInterceptor
import fr.speekha.httpmocker.Mode
import fr.speekha.httpmocker.NO_ROOT_FOLDER_ERROR
import fr.speekha.httpmocker.io.RequestWriter
import fr.speekha.httpmocker.model.ResponseDescriptor
import fr.speekha.httpmocker.policies.FilingPolicy
import fr.speekha.httpmocker.policies.MirrorPathPolicy
import fr.speekha.httpmocker.scenario.DynamicMockProvider
import fr.speekha.httpmocker.scenario.RequestCallback
import fr.speekha.httpmocker.scenario.ScenarioProvider
import fr.speekha.httpmocker.scenario.StaticMockProvider
import fr.speekha.httpmocker.serialization.Mapper
import okhttp3.Request
import java.io.File

/**
 * Builder to instantiate an interceptor.
 */
data class InterceptorBuilder internal constructor(
    private val filingPolicy: MutableList<FilingPolicy>,
    private var openFile: LoadFile?,
    private var mapper: Mapper?,
    private var simulatedDelay: Long,
    var mode: Mode,
    private val dynamicCallbacks: MutableList<RequestCallback>,
    private var showSavingErrors: Boolean
) {

    constructor() : this(
        filingPolicy = mutableListOf(),
        openFile = null,
        mapper = null,
        simulatedDelay = 0,
        mode = Mode.DISABLED,
        dynamicCallbacks = mutableListOf(),
        showSavingErrors = false
    )

    internal var recorder: RecorderBuilder? = null

    /**
     * For static mocks: Defines the policy used to retrieve the configuration files based
     * on the request being intercepted
     * @param policy the naming policy to use for scenario files
     */
    fun decodeScenarioPathWith(policy: FilingPolicy): InterceptorBuilder =
        apply { filingPolicy += policy }

    /**
     * For static mocks: Defines the policy used to retrieve the configuration files based
     * on the request being intercepted
     * @param policy a lambda to use as the naming policy for scenario files
     */
    fun decodeScenarioPathWith(policy: (Request) -> String): InterceptorBuilder =
        apply { filingPolicy += FilingPolicyBuilder(policy) }

    /**
     * For static mocks: Defines a loading function to retrieve the scenario files as a stream
     * @param loading a function to load files by name and path as a stream (could use
     * Android's assets.open, Classloader.getRessourceAsStream, FileInputStream, etc.)
     */
    fun loadFileWith(loading: FileLoader): InterceptorBuilder = apply { openFile = loading::load }

    /**
     * For static mocks: Defines a loading function to retrieve the scenario files as a stream
     * @param loading a function to load files by name and path as a stream (could use
     * Android's assets.open, Classloader.getRessourceAsStream, FileInputStream, etc.)
     */
    fun loadFileWith(loading: LoadFile): InterceptorBuilder = apply { openFile = loading }

    /**
     * Uses dynamic mocks to answer network requests instead of file scenarios
     * @param callback A callback to invoke when a request in intercepted
     */
    fun useDynamicMocks(callback: RequestCallback): InterceptorBuilder =
        apply { dynamicCallbacks += callback }

    /**
     * Uses dynamic mocks to answer network requests instead of file scenarios
     * @param callback A callback to invoke when a request in intercepted: must return a
     * ResponseDescriptor for the current Request or null if not suitable Response could be
     * computed
     */
    fun useDynamicMocks(callback: (Request) -> ResponseDescriptor?): InterceptorBuilder =
        useDynamicMocks(
            CallBackBuilder(
                callback
            )
        )

    private class CallBackBuilder(
        private val block: (Request) -> ResponseDescriptor?
    ) : RequestCallback {
        override fun loadResponse(request: Request): ResponseDescriptor? = block(request)
    }

    /**
     * Defines the mapper to use to parse the scenario files (Jackson, Moshi, GSON...)
     * @param objectMapper A Mapper to parse scenario files.
     */
    fun parseScenariosWith(objectMapper: Mapper): InterceptorBuilder =
        apply { mapper = objectMapper }

    /**
     * Defines the folder where and how scenarios should be stored when recording. This method is
     * for Java compatibility. For Kotlin users, prefer the recordScenariosIn() extension.
     * @param folder the root folder where saved scenarios should be saved
     * @param policy the naming policy to use for scenario files
     */
    fun saveScenarios(folder: File, policy: FilingPolicy?): InterceptorBuilder =
        apply { recorder = RecorderBuilder(folder, policy) }

    /**
     * Allows to return an error if saving fails when recording.
     * @param failOnError if true, failure to save scenarios will throw an exception.
     * If false, saving exceptions will be ignored.
     */
    fun failOnRecordingError(failOnError: Boolean): InterceptorBuilder =
        apply { showSavingErrors = failOnError }

    /**
     * Allows to set a fake delay for every requests (can be overridden in a scenario) to
     * achieve a more realistic behavior (probably necessary if you want to display loading
     * animations during your network calls).
     * @param delay default pause delay for network responses in ms
     */
    fun addFakeNetworkDelay(delay: Long): InterceptorBuilder = apply { simulatedDelay = delay }

    /**
     * Defines how the interceptor should initially behave (can be enabled, disable, record
     * requests...)
     * @param status The interceptor mode
     */
    fun setInterceptorStatus(status: Mode): InterceptorBuilder = apply { mode = status }

    /**
     * Builds the interceptor.
     */
    fun build(): MockResponseInterceptor = MockResponseInterceptor(
        buildProviders(),
        mapper?.let {
            RequestWriter(
                it,
                recorder?.policy ?: filingPolicy.getOrNull(0)
                ?: MirrorPathPolicy(it.supportedFormat),
                recorder?.rootFolder,
                showSavingErrors
            )
        },
        simulatedDelay
    ).apply {
        if (this@InterceptorBuilder.mode == Mode.RECORD && recorder?.rootFolder == null) {
            error(NO_ROOT_FOLDER_ERROR)
        }
        mode = this@InterceptorBuilder.mode
    }

    private fun buildProviders(): List<ScenarioProvider> {
        val dynamicMockProvider =
            dynamicCallbacks.takeIf { it.isNotEmpty() }?.let { DynamicMockProvider(it) }
        val staticMockProvider = buildStaticProvider()
        return listOfNotNull(dynamicMockProvider) + staticMockProvider
    }

    private fun buildStaticProvider(): List<StaticMockProvider> = mapper?.let { scenarioMapper ->
        openFile?.let { fileLoading ->
            if (filingPolicy.isEmpty()) {
                filingPolicy += MirrorPathPolicy(scenarioMapper.supportedFormat)
            }
            filingPolicy.map {
                StaticMockProvider(it, fileLoading, scenarioMapper)
            }
        }
    } ?: emptyList()
}
