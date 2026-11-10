package com.novaflow.cueflow

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

internal fun openSherpaRecognizer(assets: AssetManager): OnlineRecognizer {
    val model = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
    val encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
    val decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
    val joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
    val bundledFiles = assets.list(model).orEmpty()
    check(listOf(encoder, decoder, joiner, "tokens.txt").all { it in bundledFiles }) {
        "The bundled Zipformer files are missing."
    }
    return OnlineRecognizer(
        assetManager = assets,
        config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$model/$encoder",
                    decoder = "$model/$decoder",
                    joiner = "$model/$joiner",
                ),
                tokens = "$model/tokens.txt",
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 1.0f, 0f)),
            decodingMethod = "modified_beam_search",
        ),
    )
}
