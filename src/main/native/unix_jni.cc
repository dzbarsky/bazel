// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/main/native/unix_jni.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <utime.h>

#if defined(__linux__)
#include <sys/ioctl.h>
#include <sys/vfs.h>
#endif

#if defined(__linux__) && __has_include(<linux/openat2.h>)
#include <linux/openat2.h>
#endif

// Linting disabled for this line because for google code we could use
// absl::Mutex but we cannot yet because Bazel doesn't depend on absl.
#include <mutex>  // NOLINT
#include <string>
#include <vector>

#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/port.h"
#include "src/main/native/latin1_jni_path.h"

#define RESTARTABLE(_cmd, _result)                 \
  do {                                             \
    do {                                           \
      _result = _cmd;                              \
    } while ((_result == -1) && (errno == EINTR)); \
  } while (0)

#define RESTARTABLE_PTR(_cmd, _result)                  \
  do {                                                  \
    do {                                                \
      _result = _cmd;                                   \
    } while ((_result == nullptr) && (errno == EINTR)); \
  } while (0)

namespace blaze_jni {

#define FILE_BASENAME "unix_jni.cc"

struct DIROrError {
  DIR *dir;
  int error;
};

static void PostException(JNIEnv *env, const char *exception_classname,
                          const std::string &message) {
  jclass exception_class = env->FindClass(exception_classname);
  bool success = false;
  if (exception_class != nullptr) {
    success = env->ThrowNew(exception_class, message.c_str()) == 0;
  }
  if (!success) {
    BAZEL_LOG(FATAL) << "Failed to throw Java exception from JNI: "
                     << message.c_str();
  }
}

// See unix_jni.h.
void PostException(JNIEnv* env, int error_number, const std::string& message) {
  // Select the most appropriate Java exception for a given UNIX error number.
  const char *exception_classname;
  switch (error_number) {
    case EFAULT:  // Illegal pointer (unlikely; perhaps from or via FUSE?)
      exception_classname = "java/lang/IllegalArgumentException";
      break;
    case ETIMEDOUT:  // Local socket timed out
      exception_classname = "java/net/SocketTimeoutException";
      break;
    case ENOENT:  // No such file or directory
      exception_classname = "java/io/FileNotFoundException";
      break;
    case EACCES:  // Permission denied
      exception_classname =
          "com/google/devtools/build/lib/vfs/FileAccessException";
      break;
    case ENOSYS:   // Function not implemented
    case ENOTSUP:  // Operation not supported on transport endpoint
                   // (aka EOPNOTSUPP)
      exception_classname = "java/lang/UnsupportedOperationException";
      break;
    case EINVAL:  // Invalid argument
      exception_classname =
          "com/google/devtools/build/lib/unix/InvalidArgumentIOException";
      break;
    case ELOOP:  // Too many symbolic links encountered
      exception_classname =
          "com/google/devtools/build/lib/vfs/FileSymlinkLoopException";
      break;
    case EBADF:         // Bad file number or descriptor already closed.
    case ENAMETOOLONG:  // File name too long
    case ENODATA:    // No data available
#if defined(EMULTIHOP)
    case EMULTIHOP:  // Multihop attempted
#endif
    case EINTR:      // Interrupted system call
    case ENOMEM:     // Out of memory
    case EPERM:      // Operation not permitted
    case ENOLINK:    // Link has been severed
    case EIO:        // I/O error
    case EAGAIN:     // Try again
    case EFBIG:      // File too large
    case EPIPE:      // Broken pipe
    case ENOSPC:     // No space left on device
    case EXDEV:      // Cross-device link
    case EROFS:      // Read-only file system
    case EEXIST:     // File exists
    case EMLINK:     // Too many links
    case EISDIR:     // Is a directory
    case ENOTDIR:    // Not a directory
    case ENOTEMPTY:  // Directory not empty
    case EBUSY:      // Device or resource busy
    case ENFILE:     // File table overflow
    case EMFILE:     // Too many open files
    default:
      exception_classname = "java/io/IOException";
  }
  PostException(env, exception_classname,
                message + " (" + ErrorMessage(error_number) + ")");
}

static void PostAssertionError(JNIEnv *env, const std::string& message) {
  PostException(env, "java/lang/AssertionError", message);
}

// "TOSTRING" macro from: https://stackoverflow.com/a/240370
#define STRINGIFY(x) #x
#define TOSTRING(x) STRINGIFY(x)

#define POST_EXCEPTION_FROM_ERRNO(env, error, message)                       \
  PostException(env, error,                                                  \
                std::string("[" FILE_BASENAME ":" TOSTRING(__LINE__) "] ") + \
                    std::string(message))

#define POST_ASSERTION_ERROR(env, message)                              \
  PostAssertionError(                                                   \
      env, std::string("[" FILE_BASENAME ":" TOSTRING(__LINE__) "] ") + \
               std::string(message))

// Throws RuntimeExceptions for IO operations which fail unexpectedly.
// See package-info.html.
// Returns true iff an exception was thrown.
static bool PostRuntimeException(JNIEnv *env, int error_number,
                                 const char *file_path) {
  const char *exception_classname;
  switch (error_number) {
    case EFAULT:   // Illegal pointer--not likely
    case EBADF:    // Bad file number
      exception_classname = "java/lang/IllegalArgumentException";
      break;
    case ENOMEM:   // Out of memory
      exception_classname = "java/lang/OutOfMemoryError";
      break;
    case ENOTSUP:  // Operation not supported on transport endpoint
                   // (aka EOPNOTSUPP)
      exception_classname = "java/lang/UnsupportedOperationException";
      break;
    default:
      exception_classname = nullptr;
  }

  if (exception_classname == nullptr) {
    return false;
  }

  jclass exception_class = env->FindClass(exception_classname);
  if (exception_class != nullptr) {
    std::string message(file_path);
    message += " (";
    message += ErrorMessage(error_number);
    message += ")";
    env->ThrowNew(exception_class, message.c_str());
    return true;
  } else {
    BAZEL_LOG(FATAL) << "Unable to find exception_class: "
                     << exception_classname;
    return false;
  }
}

static JavaVM *GetJavaVM(JNIEnv *env) {
  static JavaVM *java_vm = nullptr;
  static std::mutex java_vm_mtx;
  std::lock_guard<std::mutex> lock(java_vm_mtx);
  if (env != nullptr) {
    JavaVM *env_java_vm;
    jint value = env->GetJavaVM(&env_java_vm);
    if (value != 0) {
      return nullptr;
    }
    if (java_vm == nullptr) {
      java_vm = env_java_vm;
    } else if (java_vm != env_java_vm) {
      return nullptr;
    }
  }
  return java_vm;
}

static void PerformIntegerValueCallback(jobject object, const char *callback,
                                        int value) {
  JavaVM *java_vm = GetJavaVM(nullptr);
  JNIEnv *java_env;
  int status = java_vm->GetEnv((void **)&java_env, JNI_VERSION_1_8);
  bool attach_current_thread = false;
  if (status == JNI_EDETACHED) {
    attach_current_thread = true;
  } else {
    BAZEL_CHECK_EQ(status, JNI_OK);
  }
  if (attach_current_thread) {
    BAZEL_CHECK_EQ(java_vm->AttachCurrentThread((void **)&java_env, nullptr),
                   0);
  }
  jclass clazz = java_env->GetObjectClass(object);
  BAZEL_CHECK_NE(clazz, nullptr);
  jmethodID method_id = java_env->GetMethodID(clazz, callback, "(I)V");
  BAZEL_CHECK_NE(method_id, nullptr);
  java_env->CallVoidMethod(object, method_id, value);

  if (attach_current_thread) {
    BAZEL_CHECK_EQ(java_vm->DetachCurrentThread(), JNI_OK);
  }
}

// TODO(bazel-team): split out all the FileSystem class's native methods
// into a separate source file, fsutils.cc.

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_readlink(JNIEnv *env,
                                                     jclass clazz,
                                                     jstring path) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return nullptr;
  }
  char target[PATH_MAX + 1];
  ssize_t len;
  RESTARTABLE(readlink(path_chars, target, arraysize(target) - 1), len);
  if (len == -1) {
    POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
    return nullptr;
  }
  target[len] = '\0';
  return NewStringLatin1(env, target);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_isCanonicalPath(
    JNIEnv *env, jclass clazz, jstring path) {
#if defined(__linux__) && defined(SYS_openat2) && defined(RESOLVE_NO_SYMLINKS)
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return -1;
  }
  // PathFragment is already absolute and lexically normalized. Rejecting every
  // symlink proves that kernel traversal cannot redirect any of its components.
  struct open_how how = {};
  how.flags = O_PATH | O_CLOEXEC;
  how.resolve = RESOLVE_NO_SYMLINKS;
  int fd;
  RESTARTABLE(syscall(SYS_openat2, AT_FDCWD, static_cast<const char*>(path_chars),
                      &how, sizeof(how)), fd);
  if (fd == -1) {
    return errno == ELOOP ? 0 : -1;
  }
  close(fd);
  return 1;
#else
  return -1;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_chmod(JNIEnv *env,
                                                  jclass clazz,
                                                  jstring path,
                                                  jint mode) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return;
  }
  int err;
  RESTARTABLE(chmod(path_chars, static_cast<int>(mode)), err);
  if (err == -1) {
    POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
  }
}

static void link_common(JNIEnv *env,
                        jstring oldpath,
                        jstring newpath,
                        int (*link_function)(const char *, const char *)) {
  JStringLatin1Holder oldpath_chars(env, oldpath);
  JStringLatin1Holder newpath_chars(env, newpath);
  if (env->ExceptionOccurred()) {
    return;
  }
  int err;
  RESTARTABLE(link_function(oldpath_chars, newpath_chars), err);
  if (err == -1) {
    POST_EXCEPTION_FROM_ERRNO(env, errno, newpath_chars);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_link(JNIEnv *env,
                                                 jclass clazz,
                                                 jstring oldpath,
                                                 jstring newpath) {
  link_common(env, oldpath, newpath, ::link);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_symlink(JNIEnv *env,
                                                    jclass clazz,
                                                    jstring oldpath,
                                                    jstring newpath) {
  link_common(env, oldpath, newpath, ::symlink);
}

namespace {

static jclass getClass(JNIEnv* env, const char* name) {
  jclass lookup_result = env->FindClass(name);
  BAZEL_CHECK_NE(lookup_result, nullptr);
  return static_cast<jclass>(env->NewGlobalRef(lookup_result));
}

static jmethodID getConstructorID(JNIEnv* env, jclass clazz, const char* sig) {
  jmethodID method = env->GetMethodID(clazz, "<init>", sig);
  BAZEL_CHECK_NE(method, nullptr) << sig;
  return method;
}

static jobject getStaticObjectField(JNIEnv* env, jclass clazz, const char* name,
                                    const char* sig) {
  jfieldID field = env->GetStaticFieldID(clazz, name, sig);
  BAZEL_CHECK_NE(field, nullptr);
  return static_cast<jobject>(
      env->NewGlobalRef(env->GetStaticObjectField(clazz, field)));
}

static jobject NewUnixFileStatus(JNIEnv *env,
                                 const portable_stat_struct &stat_ref) {
  static const jclass file_status_class =
      getClass(env, "com/google/devtools/build/lib/unix/UnixFileStatus");
  static const jmethodID file_status_class_ctor =
      getConstructorID(env, file_status_class, "(IJJJJ)V");
  return env->NewObject(
      file_status_class, file_status_class_ctor,
      static_cast<jint>(stat_ref.st_mode),
      static_cast<jlong>(StatEpochMilliseconds(stat_ref, STAT_MTIME)),
      static_cast<jlong>(StatEpochMilliseconds(stat_ref, STAT_CTIME)),
      static_cast<jlong>(stat_ref.st_size),
      static_cast<jlong>(stat_ref.st_ino));
}

}  // namespace

namespace {
static jobject StatCommon(JNIEnv *env, jstring path,
                          int (*stat_function)(const char *,
                                               portable_stat_struct *),
                          char error_handling) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return nullptr;
  }

  portable_stat_struct statbuf;
  int err;
  RESTARTABLE(stat_function(path_chars, &statbuf), err);
  if (err == -1) {
    // Save errno immediately, before we do any other syscalls.
    int saved_errno = errno;

    // Throw a RuntimeException if errno suggests a programming error.
    if (PostRuntimeException(env, saved_errno, path_chars)) {
      return nullptr;
    }

    // Throw an IOException if requested by the error handling mode.
    if (error_handling == 'a' ||
        (error_handling == 'f' && saved_errno != ENOENT &&
         saved_errno != ENOTDIR)) {
      POST_EXCEPTION_FROM_ERRNO(env, saved_errno, path_chars);
      return nullptr;
    }

    // Otherwise, return null.
    return nullptr;
  }

  return NewUnixFileStatus(env, statbuf);
}
}  // namespace

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    stat
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/FileStatus;
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_stat(
    JNIEnv *env, jclass clazz, jstring path, jchar error_handling) {
  return StatCommon(env, path, portable_stat, error_handling);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    lstat
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/FileStatus;
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_lstat(
    JNIEnv *env, jclass clazz, jstring path, jchar error_handling) {
  return StatCommon(env, path, portable_lstat, error_handling);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    utimensat
 * Signature: (Ljava/lang/String;ZJ)Z
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_utimensat(
    JNIEnv *env, jclass clazz, jstring path, jboolean now, jlong millis) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return false;
  }
  // If `now` is true use the current time, otherwise use `millis`.
  // On Linux, if the current user has write permission but isn't the owner of
  // the file, atime and mtime may be simultaneously set to UTIME_NOW, but any
  // other combination is forbidden. For simplicity, always set both.
  int64_t sec = millis / 1000;
  int32_t nsec = (millis % 1000) * 1000000;
  struct timespec times[2] = {
      {sec, now ? UTIME_NOW : nsec},
      {sec, now ? UTIME_NOW : nsec},
  };
  int err;
  RESTARTABLE(utimensat(AT_FDCWD, path_chars, times, 0), err);
  if (err == -1) {
    if (errno != ENOENT) {
      POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
    }
    return false;
  }
  return true;
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    mkdir
 * Signature: (Ljava/lang/String;I)Z
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_mkdir(JNIEnv *env,
                                                  jclass clazz,
                                                  jstring path,
                                                  jint mode) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return false;
  }

  jboolean result = true;
  int err;
  RESTARTABLE(mkdir(path_chars, mode), err);
  if (err == -1) {
    if (errno == EEXIST) {
      result = false;
    } else {
      POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
    }
  }
  return result;
}

namespace {

jobject NewDirent(JNIEnv* env, const struct dirent* e) {
  static const jclass dirent_class = getClass(
      env, "com/google/devtools/build/lib/unix/NativePosixFiles$Dirent");
  static const jmethodID dirent_ctor =
      getConstructorID(env, dirent_class,
                       "(Ljava/lang/String;Lcom/google/devtools/build/lib/unix/"
                       "NativePosixFiles$Dirent$Type;)V");
  static const jclass type_class = getClass(
      env, "com/google/devtools/build/lib/unix/NativePosixFiles$Dirent$Type");
  static const char* field_sig =
      "Lcom/google/devtools/build/lib/unix/NativePosixFiles$Dirent$Type;";
  static const jobject type_file =
      getStaticObjectField(env, type_class, "FILE", field_sig);
  static const jobject type_directory =
      getStaticObjectField(env, type_class, "DIRECTORY", field_sig);
  static const jobject type_symlink =
      getStaticObjectField(env, type_class, "SYMLINK", field_sig);
  static const jobject type_char =
      getStaticObjectField(env, type_class, "CHAR", field_sig);
  static const jobject type_block =
      getStaticObjectField(env, type_class, "BLOCK", field_sig);
  static const jobject type_fifo =
      getStaticObjectField(env, type_class, "FIFO", field_sig);
  static const jobject type_socket =
      getStaticObjectField(env, type_class, "SOCKET", field_sig);
  static const jobject type_unknown =
      getStaticObjectField(env, type_class, "UNKNOWN", field_sig);

  jstring name = NewStringLatin1(env, e->d_name);
  if (name == nullptr && env->ExceptionOccurred()) {
    return nullptr;
  }

  jobject type;
  switch (e->d_type) {
    case DT_REG:
      type = type_file;
      break;
    case DT_DIR:
      type = type_directory;
      break;
    case DT_LNK:
      type = type_symlink;
      break;
    case DT_CHR:
      type = type_char;
      break;
    case DT_BLK:
      type = type_block;
      break;
    case DT_FIFO:
      type = type_fifo;
      break;
    case DT_SOCK:
      type = type_socket;
      break;
    default:
      type = type_unknown;
      break;
  }

  return env->NewObject(dirent_class, dirent_ctor, name, type);
}

}  // namespace

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    readdir
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/Dirents;
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_readdir(JNIEnv* env,
                                                                 jclass clazz,
                                                                 jstring path) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return nullptr;
  }
  DIR *dirh;
  RESTARTABLE_PTR(opendir(path_chars), dirh);
  if (dirh == nullptr) {
    POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
  }
  if (dirh == nullptr) {
    return nullptr;
  }

  std::vector<jobject> dirents;
  for (;;) {
    // Clear errno beforehand.  Because readdir() is not required to clear it at
    // EOF, this is the only way to reliably distinguish EOF from error.
    errno = 0;
    struct dirent* entry;
    RESTARTABLE_PTR(readdir(dirh), entry);
    if (entry == nullptr) {
      if (errno == 0) break;  // EOF
      // It is unclear whether an error can also skip some records.
      // That does not appear to happen with glibc, at least.
      if (errno == EIO) continue;  // glibc returns this on transient errors
      // Otherwise, this is a real error we should report.
      POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
      closedir(dirh);
      return nullptr;
    }
    // Omit . and .. from results.
    if (entry->d_name[0] == '.') {
      if (entry->d_name[1] == '\0') continue;
      if (entry->d_name[1] == '.' && entry->d_name[2] == '\0') continue;
    }
    jobject dirent = NewDirent(env, entry);
    if (dirent == nullptr && env->ExceptionOccurred()) {
      return nullptr;
    }
    dirents.push_back(dirent);
  }

  // Do not reattempt closedir() on EINTR to avoid a double-close.
  if (closedir(dirh) < 0 && errno != EINTR) {
    POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
    return nullptr;
  }

  static const jclass dirent_class = getClass(
      env, "com/google/devtools/build/lib/unix/NativePosixFiles$Dirent");
  jobjectArray dirent_array =
      env->NewObjectArray(dirents.size(), dirent_class, nullptr);
  if (dirent_array == nullptr && env->ExceptionOccurred()) {
    return nullptr;
  }
  for (size_t i = 0; i < dirents.size(); i++) {
    env->SetObjectArrayElement(dirent_array, i, dirents[i]);
  }

  return dirent_array;
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    rename
 * Signature: (Ljava/lang/String;Ljava/lang/String;)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_rename(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring oldpath,
                                                   jstring newpath) {
  JStringLatin1Holder oldpath_chars(env, oldpath);
  JStringLatin1Holder newpath_chars(env, newpath);
  if (env->ExceptionOccurred()) {
    return;
  }
  int err;
  RESTARTABLE(rename(oldpath_chars, newpath_chars), err);
  if (err == -1) {
    std::string message(std::string(oldpath_chars) + " -> " +
                        std::string(newpath_chars));
    POST_EXCEPTION_FROM_ERRNO(env, errno, message);
  }
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    remove
 * Signature: (Ljava/lang/String;)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT bool JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_remove(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring path) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return false;
  }
  int err;
  RESTARTABLE(remove(path_chars), err);
  if (err == -1) {
    if (errno != ENOENT && errno != ENOTDIR) {
      POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
    }
  }
  return err == 0;
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    mkfifo
 * Signature: (Ljava/lang/String;I)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_mkfifo(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring path,
                                                   jint mode) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return;
  }
  int err;
  RESTARTABLE(mkfifo(path_chars, mode), err);
  if (err == -1) {
    POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
  }
}

namespace {
// Posts an exception generated by the DeleteTreesBelow algorithm and its helper
// functions.
//
// This is just a convenience wrapper over PostException to format the
// path that caused an error only when necessary, as we keep that path tokenized
// throughout the deletion process.
//
// env is the JNI environment in which to post the exception. error and function
// capture the errno value and the name of the system function that triggered
// it. The faulty path is specified by all the components of dir_path and the
// optional entry subcomponent, which may be NULL.
static void PostDeleteTreesBelowException(
    JNIEnv *env, int error, const char *function,
    const std::vector<std::string> &dir_path, const char *entry,
    const char *filename_and_line_prefix) {
  std::vector<std::string>::const_iterator iter = dir_path.begin();
  std::string path;
  if (iter != dir_path.end()) {
    path = *iter;
    while (++iter != dir_path.end()) {
      path += "/";
      path += *iter;
    }
    if (entry != nullptr) {
      path += "/";
      path += entry;
    }
  } else {
    // When scanning the top-level directory given to DeleteTreesBelow, the
    // dir_path buffer is still empty but we have the full path in entry.
    path = entry;
  }
  BAZEL_CHECK(!env->ExceptionOccurred());
  PostException(
      env, error,
      std::string(filename_and_line_prefix) + function + " (" + path + ")");
}

#define POST_DELETE_TREES_BELOW_EXCEPTION(env, error, function, dir_path, \
                                          entry)                          \
  PostDeleteTreesBelowException(env, error, function, dir_path, entry,    \
                                "[" FILE_BASENAME ":" TOSTRING(__LINE__) "] ")

// Tries to open a directory and, if the first attempt fails, retries after
// granting extra permissions to the directory.
//
// The directory to open is identified by the open descriptor of the parent
// directory (dir_fd) and the subpath to resolve within that directory (entry).
// dir_path contains the path components that were used when opening dir_fd and
// is only used for error reporting purposes.
//
// Returns a directory handle on success or an errno on error. If the error is
// other than ENOENT, posts an exception before returning.
static DIROrError ForceOpendir(JNIEnv *env,
                               const std::vector<std::string> &dir_path,
                               const int dir_fd, const char *entry) {
  static constexpr int flags = O_RDONLY | O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC;
  int fd;
  RESTARTABLE(openat(dir_fd, entry, flags), fd);
  if (fd == -1) {
    if (errno == ENOENT) {
      return {nullptr, errno};
    }
    // If dir_fd is a readable but non-executable directory containing entry, we
    // could have obtained entry by readdir()-ing, but any attempt to open or
    // stat the entry would fail with EACCESS. In this case, we need to fix the
    // permissions on dir_fd (which we can do only if it's a "real" file
    // descriptor, not AT_FDCWD used as the starting point of DeleteTreesBelow
    // recursion).
    if (errno == EACCES && dir_fd != AT_FDCWD) {
      int err;
      RESTARTABLE(fchmod(dir_fd, 0700), err);
      if (err == -1) {
        if (errno != ENOENT) {
          POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "fchmod", dir_path,
                                            nullptr);
        }
        return {nullptr, errno};
      }
    }
    int err;
    RESTARTABLE(fchmodat(dir_fd, entry, 0700, 0), err);
    if (err == -1) {
      if (errno != ENOENT) {
        POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "fchmodat", dir_path,
                                          entry);
      }
      return {nullptr, errno};
    }
    RESTARTABLE(openat(dir_fd, entry, flags), fd);
    if (fd == -1) {
      if (errno != ENOENT) {
        POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "opendir", dir_path,
                                          entry);
      }
      return {nullptr, errno};
    }
  }
  DIR* dir;
  RESTARTABLE_PTR(fdopendir(fd), dir);
  if (dir == nullptr) {
    if (errno != ENOENT) {
      POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "fdopendir", dir_path,
                                        entry);
    }
    // Do not reattempt close() on EINTR to avoid a double-close.
    close(fd);
    return {nullptr, errno != EINTR ? errno : 0};
  }
  return {dir, 0};
}

// Tries to delete a file within a directory and, if the first attempt fails,
// retries after granting extra write permissions to the directory.
//
// The file to delete is identified by the open descriptor of the parent
// directory (dir_fd) and the subpath to resolve within that directory (entry).
// dir_path contains the path components that were used when opening dir_fd and
// is only used for error reporting purposes.
//
// is_dir indicates whether the entry to delete is a directory or not.
//
// Returns 0 when the file doesn't exist or is successfully deleted. Otherwise,
// returns -1 and posts an exception.
static int ForceDelete(JNIEnv* env, const std::vector<std::string>& dir_path,
                       const int dir_fd, const char* entry,
                       const bool is_dir) {
  const int flags = is_dir ? AT_REMOVEDIR : 0;
  int err;
  RESTARTABLE(unlinkat(dir_fd, entry, flags), err);
  if (err == -1) {
    if (errno == ENOENT) {
      return 0;
    }
    RESTARTABLE(fchmod(dir_fd, 0700), err);
    if (err == -1) {
      if (errno == ENOENT) {
        return 0;
      }
      POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "fchmod", dir_path,
                                        nullptr);
      return -1;
    }
    RESTARTABLE(unlinkat(dir_fd, entry, flags), err);
    if (err == -1) {
      if (errno == ENOENT) {
        return 0;
      }
      POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "unlinkat", dir_path,
                                        entry);
      return -1;
    }
  }
  return 0;
}

// Returns true if the given directory entry represents a subdirectory of dir.
//
// The file to check is identified by the open descriptor of the parent
// directory (dir_fd) and the directory entry within that directory (de).
// dir_path contains the path components that were used when opening dir_fd and
// is only used for error reporting purposes.
//
// This function prefers to extract the type information from the directory
// entry itself if available. If not available, issues a stat starting from
// dir_fd.
//
// Returns 0 on success and updates is_dir accordingly. Returns -1 on error and
// posts an exception.
static int IsSubdir(JNIEnv* env, const std::vector<std::string>& dir_path,
                    const int dir_fd, const struct dirent* de, bool* is_dir) {
  switch (de->d_type) {
    case DT_DIR:
      *is_dir = true;
      return 0;

    case DT_UNKNOWN: {
      struct stat st;
      int err;
      RESTARTABLE(fstatat(dir_fd, de->d_name, &st, AT_SYMLINK_NOFOLLOW), err);
      if (err == -1) {
        if (errno == ENOENT) {
          *is_dir = false;
          return 0;
        }
        POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "fstatat", dir_path,
                                          de->d_name);
        return -1;
      }
      *is_dir = st.st_mode & S_IFDIR;
      return 0;
    }

    default:
      *is_dir = false;
      return 0;
  }
}

// Recursively deletes all trees under the given path.
//
// The directory to delete is identified by the open descriptor of the parent
// directory (dir_fd) and the subpath to resolve within that directory (entry).
// dir_path contains the path components that were used when opening dir_fd and
// is only used for error reporting purposes.
//
// dir_path is an in/out parameter updated with the path to the directory being
// processed. This avoids the need to construct unnecessary intermediate paths,
// as this algorithm works purely on file descriptors: the paths are only used
// for error reporting purposes, and therefore are only formatted at that
// point.
//
// Returns 0 on success. Returns -1 on error and posts an exception.
static int DeleteTreesBelow(JNIEnv* env, std::vector<std::string>* dir_path,
                            const int dir_fd, const char* entry) {
  DIROrError dir_or_error = ForceOpendir(env, *dir_path, dir_fd, entry);
  DIR *dir = dir_or_error.dir;
  if (dir == nullptr) {
    if (dir_or_error.error == ENOENT) {
      return 0;
    }
    BAZEL_CHECK_NE(env->ExceptionOccurred(), nullptr);
    return -1;
  }

  dir_path->push_back(entry);
  // On macOS and some other non-Linux OSes, on some filesystems, readdir(dir)
  // may return NULL after an entry in dir is deleted even if not all files have
  // been read yet - see
  // https://pubs.opengroup.org/onlinepubs/9699919799/functions/readdir.html;
  // "If a file is removed from or added to the directory after the most recent
  // call to opendir() or rewinddir(), whether a subsequent call to readdir()
  // returns an entry for that file is unspecified." We thus read all the names
  // of dir's entries before deleting. We don't want to simply use fts(3)
  // because we want to be able to chmod at any point in the directory hierarchy
  // to retry a filesystem operation after hitting an EACCES.
  // If in the future we hit any problems here due to the unspecified behavior
  // of readdir() when a file has been deleted by a different thread we can use
  // some form of locking to make sure the threads don't try to clean up the
  // same directory at the same time; or doing it in a loop until the directory
  // is really empty.
  std::vector<std::string> dir_files, dir_subdirs;
  for (;;) {
    errno = 0;
    struct dirent* entry;
    RESTARTABLE_PTR(readdir(dir), entry);
    if (entry == nullptr) {
      if (errno != 0 && errno != ENOENT) {
        POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "readdir", *dir_path,
                                          nullptr);
      }
      break;
    }

    if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
      continue;
    }

    bool is_dir;
    if (IsSubdir(env, *dir_path, dirfd(dir), entry, &is_dir) == -1) {
      BAZEL_CHECK_NE(env->ExceptionOccurred(), nullptr);
      break;
    }
    if (is_dir) {
      dir_subdirs.push_back(entry->d_name);
    } else {
      dir_files.push_back(entry->d_name);
    }
  }
  if (env->ExceptionOccurred() == nullptr) {
    for (const auto &file : dir_files) {
      if (ForceDelete(env, *dir_path, dirfd(dir), file.c_str(), false) == -1) {
        BAZEL_CHECK_NE(env->ExceptionOccurred(), nullptr);
        break;
      }
    }
    // DeleteTreesBelow is recursive; don't hold on to file names unnecessarily.
    dir_files.clear();
  }
  if (env->ExceptionOccurred() == nullptr) {
    for (const auto &subdir : dir_subdirs) {
      if (DeleteTreesBelow(env, dir_path, dirfd(dir), subdir.c_str()) == -1) {
        BAZEL_CHECK_NE(env->ExceptionOccurred(), nullptr);
        break;
      }
      if (ForceDelete(env, *dir_path, dirfd(dir), subdir.c_str(), true) == -1) {
        BAZEL_CHECK_NE(env->ExceptionOccurred(), nullptr);
        break;
      }
    }
  }
  // Do not reattempt closedir() on EINTR to avoid a double-close.
  if (closedir(dir) == -1 && errno != EINTR) {
    // Prefer reporting the error encountered while processing entries,
    // not the (unlikely) error on close.
    if (env->ExceptionOccurred() == nullptr) {
      POST_DELETE_TREES_BELOW_EXCEPTION(env, errno, "closedir", *dir_path,
                                        nullptr);
    }
  }
  dir_path->pop_back();
  return env->ExceptionOccurred() == nullptr ? 0 : -1;
}
}  // namespace

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    deleteTreesBelow
 * Signature: (Ljava/lang/String;)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_deleteTreesBelow(
    JNIEnv *env, jclass clazz, jstring path) {
  JStringLatin1Holder path_chars(env, path);
  if (env->ExceptionOccurred()) {
    return;
  }
  std::vector<std::string> dir_path;
  if (DeleteTreesBelow(env, &dir_path, AT_FDCWD, path_chars) == -1) {
    BAZEL_CHECK_NE(env->ExceptionOccurred(), nullptr);
  }
  BAZEL_CHECK(dir_path.empty());
}

////////////////////////////////////////////////////////////////////////
// Linux extended file attributes

namespace {
typedef ssize_t getxattr_func(const char *path, const char *name,
                              void *value, size_t size, bool *attr_not_found);

static jbyteArray getxattr_common(JNIEnv *env,
                                  jstring path,
                                  jstring name,
                                  getxattr_func getxattr) {
  JStringLatin1Holder path_chars(env, path);
  JStringLatin1Holder name_chars(env, name);
  if (env->ExceptionOccurred()) {
    return nullptr;
  }

  // TODO(bazel-team): on ERANGE, try again with larger buffer.
  jbyte value[4096];
  jbyteArray result = nullptr;
  bool attr_not_found = false;
  ssize_t size;
  RESTARTABLE(size = getxattr(path_chars, name_chars, value, arraysize(value),
                              &attr_not_found),
              size);
  if (size == -1) {
    if (!attr_not_found) {
      POST_EXCEPTION_FROM_ERRNO(env, errno, path_chars);
    }
  } else {
    result = env->NewByteArray(size);
    // Result may be NULL if allocation failed. In that case, we'll return the
    // NULL and an OOME will be thrown when we are back in Java.
    if (result != nullptr) {
      env->SetByteArrayRegion(result, 0, size, value);
    }
  }
  return result;
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_getxattr(JNIEnv *env,
                                                     jclass clazz,
                                                     jstring path,
                                                     jstring name) {
  return getxattr_common(env, path, name, portable_getxattr);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_lgetxattr(JNIEnv *env,
                                                      jclass clazz,
                                                      jstring path,
                                                      jstring name) {
  return getxattr_common(env, path, name, portable_lgetxattr);
}

namespace {
// Timber's bounded directory ioctl. The request size distinguishes this layout
// from metadata-only versions; other filesystems use ordinary syscalls below.
struct TimberStatEntry {
  uint64_t pathname;
  int32_t error;
  uint32_t mode;
  uint64_t inode;
  int64_t size;
  int64_t mtime_sec;
  int64_t ctime_sec;
  uint32_t mtime_nsec;
  uint32_t ctime_nsec;
  int32_t digest_size;
  uint8_t digest[32];
  uint32_t reserved;
};

bool TimberStatBatch(const char* root, const std::vector<std::string>& paths,
                     const char* xattr, std::vector<TimberStatEntry>* entries) {
#if defined(__linux__)
  struct Request {
    uint64_t entries;
    uint32_t count;
    int32_t dirfd;
    uint32_t flags;
    uint32_t reserved;
    uint64_t digest_xattr;
  };
  static_assert(sizeof(Request) == 32);
  static_assert(sizeof(TimberStatEntry) == 96);
  int fd;
  RESTARTABLE(open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC), fd);
  if (fd < 0) return false;
  struct statfs fs;
  bool supported = false;
  if (fstatfs(fd, &fs) == 0 && fs.f_type == 0x54494d4b) {
    entries->resize(paths.size());
    for (size_t i = 0; i < paths.size(); ++i) {
      (*entries)[i].pathname = reinterpret_cast<uint64_t>(paths[i].c_str());
    }
    Request request = {reinterpret_cast<uint64_t>(entries->data()),
                       static_cast<uint32_t>(entries->size()),
                       fd,
                       0,
                       0,
                       *xattr ? reinterpret_cast<uint64_t>(xattr) : 0};
    supported = ioctl(fd, _IOWR(0xb7, 1, Request), &request) == 0;
  }
  close(fd);
  return supported;
#else
  return false;
#endif
}

struct TimberCreateEntry {
  int32_t dirfd;
  int32_t error;
  uint32_t name_offset;
  uint32_t target_offset;
};

// False means unsupported before writes, or an exception already posted to Java.
// Mutating ioctls must never be retried after an ambiguous failure.
bool TimberCreateBatch(JNIEnv* env, jstring root, jobjectArray paths,
                      jobjectArray targets,
                      std::vector<TimberCreateEntry>* entries) {
#if defined(__linux__)
  constexpr size_t kMaxBytes = 1024 * 1024;
  struct Request {
    uint64_t entries;
    uint64_t strings;
    uint32_t count;
    uint32_t string_bytes;
    uint32_t flags;
    uint32_t reserved;
  };
  static_assert(sizeof(Request) == 32);
  static_assert(sizeof(TimberCreateEntry) == 16);
  JStringLatin1Holder root_chars(env, root);
  if (env->ExceptionCheck()) return false;
  if (strlen(root_chars) != static_cast<size_t>(env->GetStringLength(root))) {
    POST_EXCEPTION_FROM_ERRNO(env, EINVAL, root_chars);
    return false;
  }
  const jsize count = env->GetArrayLength(paths);
  if (count > 4096 ||
      (targets != nullptr && env->GetArrayLength(targets) != count)) {
    POST_EXCEPTION_FROM_ERRNO(env, EINVAL, root_chars);
    return false;
  }
  std::vector<char> strings;
  entries->resize(count);
  for (jsize i = 0; i < count; ++i) {
    for (int field = 0; field < 2; ++field) {
      uint32_t offset = strings.size();
      if (field == 0) {
        (*entries)[i].name_offset = offset;
      } else {
        (*entries)[i].target_offset = offset;
      }
      if (field == 1 && targets == nullptr) {
        if (strings.size() == kMaxBytes) {
          POST_EXCEPTION_FROM_ERRNO(env, E2BIG, root_chars);
          return false;
        }
        strings.push_back(0);
        continue;
      }
      jstring value = static_cast<jstring>(
          env->GetObjectArrayElement(field == 0 ? paths : targets, i));
      if (value == nullptr) {
        if (!env->ExceptionCheck()) {
          POST_EXCEPTION_FROM_ERRNO(env, EINVAL, root_chars);
        }
        return false;
      }
      const size_t length = env->GetStringLength(value);
      if (length + 1 > kMaxBytes - strings.size()) {
        env->DeleteLocalRef(value);
        POST_EXCEPTION_FROM_ERRNO(env, E2BIG, root_chars);
        return false;
      }
      {
        JStringLatin1Holder chars(env, value);
        if (env->ExceptionCheck()) return false;
        if (strlen(chars) != length) {
          env->DeleteLocalRef(value);
          POST_EXCEPTION_FROM_ERRNO(env, EINVAL, root_chars);
          return false;
        }
        const char* data = chars;
        strings.insert(strings.end(), data, data + length + 1);
      }
      env->DeleteLocalRef(value);
    }
  }
  int fd;
  RESTARTABLE(open(root_chars, O_RDONLY | O_DIRECTORY | O_CLOEXEC), fd);
  if (fd < 0) return false;
  struct statfs fs;
  if (fstatfs(fd, &fs) < 0 || fs.f_type != 0x54494d4b) {
    close(fd);
    return false;
  }
  for (auto& entry : *entries) entry.dirfd = fd;
  Request request = {reinterpret_cast<uint64_t>(entries->data()),
                     reinterpret_cast<uint64_t>(strings.data()),
                     static_cast<uint32_t>(entries->size()),
                     static_cast<uint32_t>(strings.size()),
                     targets == nullptr ? 1u : 0u,
                     0};
  const int result = ioctl(fd, _IOWR(0xb7, 2, Request), &request);
  const int error = result < 0 ? errno : 0;
  close(fd);
  if (error == ENOTTY) return false;
  if (error != 0) {
    POST_EXCEPTION_FROM_ERRNO(env, error, root_chars);
    return false;
  }
  return true;
#else
  return false;
#endif
}

jobject WithDigest(JNIEnv* env, jobject stat, const jbyte* digest, int size) {
  static const jclass adapter_class = getClass(
      env, "com/google/devtools/build/lib/vfs/FileStatusWithDigestAdapter");
  static const jmethodID constructor =
      getConstructorID(env, adapter_class,
                       "(Lcom/google/devtools/build/lib/vfs/FileStatus;[B)V");
  jbyteArray bytes = env->NewByteArray(size);
  if (bytes == nullptr) return nullptr;
  env->SetByteArrayRegion(bytes, 0, size, digest);
  jobject result = env->NewObject(adapter_class, constructor, stat, bytes);
  env->DeleteLocalRef(bytes);
  return result;
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_statBatch(
    JNIEnv* env, jclass clazz, jstring root, jobjectArray relative_paths,
    jstring digest_xattr, jboolean use_ioctl) {
  JStringLatin1Holder root_chars(env, root);
  JStringLatin1Holder xattr_chars(env, digest_xattr);
  if (env->ExceptionCheck()) return nullptr;
  const jsize count = env->GetArrayLength(relative_paths);
  if (count > 4096) {
    POST_EXCEPTION_FROM_ERRNO(env, E2BIG, root_chars);
    return nullptr;
  }
  static const jclass status_class =
      getClass(env, "com/google/devtools/build/lib/vfs/FileStatus");
  jobjectArray result = env->NewObjectArray(count, status_class, nullptr);
  if (result == nullptr || count == 0) return result;
  std::vector<std::string> paths;
  paths.reserve(count);
  for (jsize i = 0; i < count; ++i) {
    jstring path =
        static_cast<jstring>(env->GetObjectArrayElement(relative_paths, i));
    {
      JStringLatin1Holder chars(env, path);
      if (env->ExceptionCheck()) return nullptr;
      paths.emplace_back(chars);
    }
    env->DeleteLocalRef(path);
  }
  std::vector<TimberStatEntry> entries;
  const bool batched =
      use_ioctl && TimberStatBatch(root_chars, paths, xattr_chars, &entries);
  std::string prefix(root_chars);
  if (prefix.back() != '/') prefix += '/';
  for (jsize i = 0; i < count; ++i) {
    jobject stat = nullptr;
    // Match getxattr_common: FreeBSD returns bytes copied even when truncated.
    jbyte digest[4096];
    int digest_size = -1;
    const auto& entry = batched ? entries[i] : TimberStatEntry{};
    if (batched && (entry.error == -ENOENT || entry.error == -ENOTDIR))
      continue;
    if (batched && entry.error == 0) {
      static const jclass unix_status_class =
          getClass(env, "com/google/devtools/build/lib/unix/UnixFileStatus");
      static const jmethodID constructor =
          getConstructorID(env, unix_status_class, "(IJJJJ)V");
      stat = env->NewObject(
          unix_status_class, constructor, static_cast<jint>(entry.mode),
          static_cast<jlong>(entry.mtime_sec * 1000 +
                             entry.mtime_nsec / 1000000),
          static_cast<jlong>(entry.ctime_sec * 1000 +
                             entry.ctime_nsec / 1000000),
          static_cast<jlong>(entry.size), static_cast<jlong>(entry.inode));
      if (*static_cast<const char*>(xattr_chars) && S_ISREG(entry.mode) &&
          entry.digest_size >= 0 && entry.digest_size <= 32) {
        digest_size = entry.digest_size;
        memcpy(digest, entry.digest, digest_size);
      }
    } else {
      std::string path = prefix + paths[i];
      portable_stat_struct metadata;
      int rc;
      RESTARTABLE(portable_lstat(path.c_str(), &metadata), rc);
      if (rc < 0) {
        int error = errno;
        if (error == ENOENT || error == ENOTDIR) continue;
        POST_EXCEPTION_FROM_ERRNO(env, error, path.c_str());
        return nullptr;
      }
      stat = NewUnixFileStatus(env, metadata);
      if (*static_cast<const char*>(xattr_chars) && S_ISREG(metadata.st_mode)) {
        bool absent = false;
        RESTARTABLE(portable_lgetxattr(path.c_str(), xattr_chars, digest,
                                       sizeof(digest), &absent),
                    digest_size);
        // Failed or oversized xattrs leave the existing scalar digest fallback
        // in charge.
      }
    }
    if (env->ExceptionCheck()) return nullptr;
    if (digest_size >= 0) {
      jobject with_digest = WithDigest(env, stat, digest, digest_size);
      env->DeleteLocalRef(stat);
      stat = with_digest;
    }
    if (env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(result, i, stat);
    env->DeleteLocalRef(stat);
  }
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_createDirectoriesBatch(
    JNIEnv* env, jclass clazz, jstring root, jobjectArray paths) {
  std::vector<TimberCreateEntry> entries;
  if (!TimberCreateBatch(env, root, paths, nullptr, &entries)) return -1;
  jint created = 0;
  for (const auto& entry : entries) {
    if (entry.error != 0) break;
    ++created;
  }
  return created;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_createSymlinksBatch(
    JNIEnv* env, jclass clazz, jstring root, jobjectArray paths,
    jobjectArray targets) {
  std::vector<TimberCreateEntry> entries;
  if (!TimberCreateBatch(env, root, paths, targets, &entries)) return nullptr;
  jintArray result = env->NewIntArray(entries.size());
  if (result == nullptr) return nullptr;
  std::vector<jint> errors;
  errors.reserve(entries.size());
  for (const auto& entry : entries) errors.push_back(entry.error);
  env->SetIntArrayRegion(result, 0, errors.size(), errors.data());
  return result;
}

/*
 * Class:     com_google_devtools_build_lib_platform_SleepPreventionModule_SleepPrevention
 * Method:    pushDisableSleep
 * Signature: ()I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_platform_SleepPreventionModule_00024SleepPrevention_pushDisableSleep(
    JNIEnv *, jclass) {
  return portable_push_disable_sleep();
}

/*
 * Class:     com_google_devtools_build_lib_platform_SleepPreventionModule_SleepPrevention
 * Method:    popDisableSleep
 * Signature: ()I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_platform_SleepPreventionModule_00024SleepPrevention_popDisableSleep(
    JNIEnv *, jclass) {
  return portable_pop_disable_sleep();
}

jobject g_suspend_module;

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemSuspensionModule
 * Method:    registerJNI
 * Signature: ()V
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_platform_SystemSuspensionModule_registerJNI(
    JNIEnv *env, jobject local_object) {

  if (g_suspend_module != nullptr) {
    POST_ASSERTION_ERROR(env,
                         "Singleton SystemSuspensionModule already registered");
    return;
  }

  JavaVM *java_vm = GetJavaVM(env);
  if (java_vm == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to get javaVM registering SystemSuspensionModule");
    return;
  }

  g_suspend_module = env->NewGlobalRef(local_object);
  if (g_suspend_module == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to create global ref for SystemSuspensionModule");
    return;
  }
  portable_start_suspend_monitoring();
}

void suspend_callback(SuspensionReason value) {
  if (g_suspend_module != nullptr) {
    PerformIntegerValueCallback(g_suspend_module, "suspendCallback", value);
  }
}

jobject g_thermal_module;

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemThermalModule
 * Method:    registerJNI
 * Signature: ()V
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_platform_SystemThermalModule_registerJNI(
    JNIEnv *env, jobject local_object) {

  if (g_thermal_module != nullptr) {
    POST_ASSERTION_ERROR(env,
                         "Singleton SystemThermalModule already registered");
    return;
  }

  JavaVM *java_vm = GetJavaVM(env);
  if (java_vm == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to get javaVM registering SystemThermalModule");
    return;
  }

  g_thermal_module = env->NewGlobalRef(local_object);
  if (g_thermal_module == nullptr) {
    POST_ASSERTION_ERROR(env,
                         "Unable to create global ref for SystemThermalModule");
    return;
  }
  portable_start_thermal_monitoring();
}

void thermal_callback(int value) {
  if (g_thermal_module != nullptr) {
    PerformIntegerValueCallback(g_thermal_module, "thermalCallback", value);
  }
}

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemThermalModule
 * Method:    thermalLoad
 * Signature: ()I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_platform_SystemThermalModule_thermalLoad(
    JNIEnv *env, jclass) {
  return portable_thermal_load();
}

jobject g_system_load_advisory_module;

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemLoadAdvisoryModule
 * Method:    registerJNI
 * Signature: ()V
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_platform_SystemLoadAdvisoryModule_registerJNI(
    JNIEnv *env, jobject local_object) {

  if (g_system_load_advisory_module != nullptr) {
    POST_ASSERTION_ERROR(
        env, "Singleton SystemLoadAdvisoryModule already registered");
    return;
  }

  JavaVM *java_vm = GetJavaVM(env);
  if (java_vm == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to get javaVM registering SystemLoadAdvisoryModule");
    return;
  }

  g_system_load_advisory_module = env->NewGlobalRef(local_object);
  if (g_system_load_advisory_module == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to create global ref for SystemLoadAdvisoryModule");
    return;
  }
  portable_start_system_load_advisory_monitoring();
}

void system_load_advisory_callback(int value) {
  if (g_system_load_advisory_module != nullptr) {
    PerformIntegerValueCallback(g_system_load_advisory_module,
                                "systemLoadAdvisoryCallback", value);
  }
}

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemLoadAdvisoryModule
 * Method:    systemLoadAdvisory
 * Signature: ()I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_platform_SystemLoadAdvisoryModule_systemLoadAdvisory(
    JNIEnv *env, jclass) {
  return portable_system_load_advisory();
}

jobject g_memory_pressure_module;

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemMemoryPressureMonitor
 * Method:    registerJNI
 * Signature: ()I
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_platform_SystemMemoryPressureMonitor_registerJNI(
    JNIEnv *env, jobject local_object) {

  if (g_memory_pressure_module != nullptr) {
    POST_ASSERTION_ERROR(
        env, "Singleton SystemMemoryPressureModule already registered");
    return;
  }

  JavaVM *java_vm = GetJavaVM(env);
  if (java_vm == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to get javaVM registering SystemMemoryPressureModule");
    return;
  }

  g_memory_pressure_module = env->NewGlobalRef(local_object);
  if (g_memory_pressure_module == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to create global ref for SystemMemoryPressureModule");
    return;
  }
  portable_start_memory_pressure_monitoring();
}

void memory_pressure_callback(MemoryPressureLevel level) {
  if (g_memory_pressure_module != nullptr) {
    PerformIntegerValueCallback(g_memory_pressure_module,
                                "memoryPressureCallback", level);
  }
}

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemMemoryPressureMonitor
 * Method:    systemMemoryPressure
 * Signature: ()I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_platform_SystemMemoryPressureMonitor_systemMemoryPressure(
    JNIEnv *env, jclass) {
  return portable_memory_pressure();
}

jobject g_disk_space_module;

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemDiskSpaceModule
 * Method:    registerJNI
 * Signature: ()I
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_platform_SystemDiskSpaceModule_registerJNI(
    JNIEnv *env, jobject local_object) {

  if (g_disk_space_module != nullptr) {
    POST_ASSERTION_ERROR(env,
                         "Singleton SystemDiskSpaceModule already registered");
    return;
  }

  JavaVM *java_vm = GetJavaVM(env);
  if (java_vm == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to get javaVM registering SystemDiskSpaceModule");
    return;
  }

  g_disk_space_module = env->NewGlobalRef(local_object);
  if (g_disk_space_module == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to create global ref for SystemDiskSpaceModule");
    return;
  }
  portable_start_disk_space_monitoring();
}

void disk_space_callback(DiskSpaceLevel level) {
  if (g_disk_space_module != nullptr) {
    PerformIntegerValueCallback(g_disk_space_module, "diskSpaceCallback",
                                level);
  }
}

jobject g_cpu_speed_module;

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemCPUSpeedModule
 * Method:    registerJNI
 * Signature: ()I
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_platform_SystemCPUSpeedModule_registerJNI(
    JNIEnv *env, jobject local_object) {

  if (g_cpu_speed_module != nullptr) {
    POST_ASSERTION_ERROR(env,
                         "Singleton SystemCPUSpeedModule already registered");
    return;
  }

  JavaVM *java_vm = GetJavaVM(env);
  if (java_vm == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to get javaVM registering SystemCPUSpeedModule");
    return;
  }

  g_cpu_speed_module = env->NewGlobalRef(local_object);
  if (g_cpu_speed_module == nullptr) {
    POST_ASSERTION_ERROR(
        env, "Unable to create global ref for SystemCPUSpeedModule");
    return;
  }
  portable_start_cpu_speed_monitoring();
}

void cpu_speed_callback(int speed) {
  if (g_cpu_speed_module != nullptr) {
    PerformIntegerValueCallback(g_cpu_speed_module, "cpuSpeedCallback", speed);
  }
}

/*
 * Class:     Java_com_google_devtools_build_lib_platform_SystemCPUSpeedModule
 * Method:    cpuSpeed
 * Signature: ()I
 *
 * Returns 1-100 to represent CPU speed. Returns -1 in case of error.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_platform_SystemCPUSpeedModule_cpuSpeed(
    JNIEnv *env, jclass) {
  return portable_cpu_speed();
}

}  // namespace blaze_jni
