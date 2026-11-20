cmake_minimum_required(VERSION 3.22.1)
project(lvyoubao_rnnoise C)

set(CMAKE_C_STANDARD 99)

# RNNoise（xiph/rnnoise，BSD-3）：48kHz、10ms 一帧的小型 GRU 降噪器。
# 只保留推理所需的 6 个源文件，训练相关代码不进包。
add_library(rnnoise_jni SHARED
    rnnoise_jni.c
    rnnoise/src/denoise.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/pitch.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
    rnnoise/src/rnn_reader.c
)

target_include_directories(rnnoise_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src
)

# -O3 + ffast-math：这台设备是 32 位 ARM，降噪要跑在采集线程里，
# 每 10ms 帧的预算必须卡住，浮点近似对降噪音质没有可听影响。
# FLOAT_APPROX 是 libopus/RNNoise 的开关：不开它就不允许 -ffast-math
# （arch.h 会直接 #error），它把 celt 的浮点工具换成 ARM 友好的近似实现。
target_compile_options(rnnoise_jni PRIVATE
    -O3
    -ffast-math
    -DFLOAT_APPROX
    -Wno-unused-function
    -Wno-unused-variable
)

target_link_libraries(rnnoise_jni log m)
