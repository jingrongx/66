/*
 * RNNoise 的 JNI 包装。
 *
 * 设计要点：
 * - 一次调用处理整帧（480 的整数倍），避免每 10ms 过一次 JNI 边界；
 * - 就地改写 ShortArray（16bit PCM，±32768），调用方不必再分配缓冲；
 * - 返回这一帧的平均「人声概率」（RNNoise 自带 VAD 输出，0~1），
 *   它比能量判据可靠得多，供上层判活参考。
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "rnnoise.h"

#define MAX_FRAME 480

JNIEXPORT jint JNICALL
Java_com_lvyoubao_app_ui_common_RnNoise_nativeFrameSize(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return rnnoise_get_frame_size();
}

JNIEXPORT jlong JNICALL
Java_com_lvyoubao_app_ui_common_RnNoise_nativeCreate(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    DenoiseState *st = rnnoise_create(NULL);
    return (jlong) (intptr_t) st;
}

JNIEXPORT void JNICALL
Java_com_lvyoubao_app_ui_common_RnNoise_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    if (st != NULL) rnnoise_destroy(st);
}

JNIEXPORT jfloat JNICALL
Java_com_lvyoubao_app_ui_common_RnNoise_nativeProcess(
        JNIEnv *env, jobject thiz, jlong handle, jshortArray buf, jint count) {
    (void) thiz;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    if (st == NULL || count <= 0) return -1.0f;

    const int frame = rnnoise_get_frame_size();
    const int blocks = count / frame;
    if (blocks <= 0) return -1.0f;

    jshort *pcm = (*env)->GetShortArrayElements(env, buf, NULL);
    if (pcm == NULL) return -1.0f;

    float fin[MAX_FRAME];
    float fout[MAX_FRAME];
    double vadSum = 0.0;
    int b, i;
    for (b = 0; b < blocks; b++) {
        for (i = 0; i < frame; i++) fin[i] = (float) pcm[b * frame + i];
        float vad = rnnoise_process_frame(st, fout, fin);
        vadSum += vad;
        for (i = 0; i < frame; i++) {
            float v = fout[i];
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            pcm[b * frame + i] = (jshort) v;
        }
    }

    (*env)->ReleaseShortArrayElements(env, buf, pcm, 0);
    return (jfloat) (vadSum / blocks);
}
