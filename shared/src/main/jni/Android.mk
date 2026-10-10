LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

# The car hop's native half, when this build has one. Its makefile lives with the rest of the hop,
# outside this repository, and VENDOR_HOP_JNI is where the build says that is. Last, because the
# fragment sets LOCAL_PATH to its own directory.
ifdef VENDOR_HOP_JNI
include $(VENDOR_HOP_JNI)/Android.mk
endif
