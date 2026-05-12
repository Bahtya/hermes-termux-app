LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libpath_rewrite
LOCAL_SRC_FILES := path_rewrite.c
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
