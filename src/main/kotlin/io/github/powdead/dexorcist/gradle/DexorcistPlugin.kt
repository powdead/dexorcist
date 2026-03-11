package io.github.powdead.dexorcist.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class DexorcistPlugin : Plugin<Project> {

    companion object {
        const val VERSION = "0.2.1"
        const val MULTIDEX_VERSION = "2.0.1"
        const val DESUGAR_VERSION = "2.0.4"
        const val SHIM_MAX_API = 17
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create("dexorcist", DexorcistExtension::class.java)
        project.plugins.withType(AppPlugin::class.java) { configureAndroid(project, ext) }
    }

    private fun configureAndroid(project: Project, ext: DexorcistExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

        project.dependencies.add("implementation", "androidx.multidex:multidex:$MULTIDEX_VERSION")
        project.dependencies.add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:$DESUGAR_VERSION")
        project.dependencies.add("implementation", "io.github.powdead:dexorcist-shims:$VERSION")

        val stubsTask = project.tasks.register(
            "dexorcistPlatformStubs", DexorcistPlatformStubsTask::class.java
        ) { output.set(project.layout.buildDirectory.file("dexorcist/platform-stubs.jar")) }
        project.dependencies.add("implementation", project.files(stubsTask.flatMap { it.output }))

        components.finalizeDsl { dsl ->
            dsl.defaultConfig.multiDexEnabled = true
            dsl.compileOptions.isCoreLibraryDesugaringEnabled = true

            if (ext.enableArmeabi.get()) {
                dsl.defaultConfig.ndk {
                    if (abiFilters.isEmpty())
                        abiFilters += setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                    else abiFilters += "armeabi"
                }
                if (ext.dexorcistNdkPath.get().isNotEmpty())
                    dsl.ndkPath = ext.dexorcistNdkPath.get()
            }

            generateNativeShim(project)

            val cmakeInclude = project.layout.buildDirectory
                .file("dexorcist/native-shim/dexorcist_shim.cmake").get().asFile
            val shimCmakePath = cmakeInclude.absolutePath.replace('\\', '/')

            if (ext.splitApk.get()) {
                dsl.flavorDimensions += "dexorcist"
                dsl.productFlavors.create("stock") { dimension = "dexorcist" }
                dsl.productFlavors.create("shimmed") {
                    dimension = "dexorcist"
                    val abis = ext.splitAbis.get()
                    if (abis.isNotEmpty()) ndk { abiFilters.clear(); abiFilters.addAll(abis) }
                    externalNativeBuild.cmake.apply {
                        arguments.addAll(listOf(
                            "-DANDROID_STL=c++_static",
                            "-DCMAKE_PROJECT_INCLUDE=$shimCmakePath",
                            "-DDEXORCIST_LEGACY_X86=ON"
                        ))
                        cppFlags.addAll(listOf("-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0"))
                        cFlags.addAll(listOf("-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0"))
                    }
                }
            } else {
                dsl.defaultConfig.externalNativeBuild.cmake.apply {
                    val newArgs = arguments.map {
                        if (it.contains("c++_shared")) it.replace("c++_shared", "c++_static") else it
                    }.toMutableList()
                    newArgs.add("-DCMAKE_PROJECT_INCLUDE=$shimCmakePath")
                    newArgs.add("-DDEXORCIST_LEGACY_X86=ON")
                    arguments.clear()
                    arguments.addAll(newArgs)

                    cppFlags.addAll(listOf("-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0"))
                    cFlags.addAll(listOf("-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0"))
                }
            }
        }

        components.onVariants { variant ->
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            val isStock = ext.splitApk.get() && variant.name.contains("stock", ignoreCase = true)

            if (variant.minSdk.apiLevel < SHIM_MAX_API) {
                variant.instrumentation.transformClassesWith(
                    DexorcistApiShimTransform::class.java, InstrumentationScope.ALL
                ) {}
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }

            if (!isStock) {
                variant.instrumentation.transformClassesWith(
                    DexorcistNativeShimTransform::class.java, InstrumentationScope.PROJECT
                ) {}
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }

            val manifestTask = project.tasks.register(
                "dexorcistManifestOverride$cap", DexorcistManifestOverrideTask::class.java
            ) {
                group = "dexorcist"
                val rc = project.configurations.findByName("${variant.name}RuntimeClasspath")
                if (rc != null) dependencies.from(rc)
                outputManifest.set(project.layout.buildDirectory.file("dexorcist/${variant.name}/override-manifest.xml"))
            }
            variant.sources.manifests.addGeneratedManifestFile(manifestTask, DexorcistManifestOverrideTask::outputManifest)

            if (ext.downgradeResources.get()) {
                val resTask = project.tasks.register(
                    "dexorcistDowngradeResources$cap", DexorcistResourceDowngradeTask::class.java
                ) { group = "dexorcist" }
                val req = variant.artifacts.use(resTask)
                    .wiredWithDirectories(DexorcistResourceDowngradeTask::inputApkDir, DexorcistResourceDowngradeTask::outputApkDir)
                    .toTransformMany(SingleArtifact.APK)
                resTask.configure { transformationRequest.set(req) }
            }
        }
    }

    private fun generateNativeShim(project: Project) {
        val shimDir = project.layout.buildDirectory.dir("dexorcist/native-shim").get().asFile
        shimDir.mkdirs()

        val cmakeFile = File(shimDir, "dexorcist_shim.cmake")
        cmakeFile.writeText("""
            # Dexorcist: provide stubs for symbols missing on old Android (< API 21)
            file(WRITE "${'$'}{CMAKE_CURRENT_BINARY_DIR}/_dexorcist_shim.c" [==[
            #include <string.h>
            #include <stdlib.h>
            #include <stdio.h>
            #include <stdarg.h>
            #include <unistd.h>
            #include <dlfcn.h>
            #include <sys/select.h>
            #include <wchar.h>
            #include <wctype.h>
            #include <time.h>
            #include <ctype.h>
            #include <errno.h>
            #include <locale.h>
            #include <math.h>

            #define SHIM __attribute__((visibility("default")))

            /* ---- API 21: android_set_abort_message ---- */
            SHIM void android_set_abort_message(const char* msg) { (void)msg; }

            /* ---- API 21: signal (API 9 has bsd_signal) ---- */
            typedef void (*_dex_sighandler_t)(int);
            typedef _dex_sighandler_t (*_dex_signal_fn)(int, _dex_sighandler_t);
            SHIM _dex_sighandler_t signal(int sig, _dex_sighandler_t handler) {
                static _dex_signal_fn impl = 0;
                if (!impl) {
                    impl = (_dex_signal_fn)dlsym((void*)-1, "bsd_signal");
                    if (!impl) impl = (_dex_signal_fn)dlsym((void*)-2, "signal");
                }
                return impl ? impl(sig, handler) : (_dex_sighandler_t)-1;
            }

            /* ---- API 21: posix_memalign ---- */
            SHIM int posix_memalign(void** memptr, size_t alignment, size_t size) {
                void* p = memalign(alignment, size);
                if (!p) return ENOMEM;
                *memptr = p;
                return 0;
            }

            /* ---- API 21: at_quick_exit / quick_exit ---- */
            SHIM int at_quick_exit(void (*func)(void)) { return atexit(func); }
            SHIM void quick_exit(int status) { _exit(status); }

            /* ---- C99 strto* (missing before API 21) ---- */
            SHIM float strtof(const char* s, char** end) { return (float)strtod(s, end); }
            SHIM long double strtold(const char* s, char** end) { return (long double)strtod(s, end); }

            /* ---- Locale infrastructure (API 21+) ---- */
            static int _dex_dummy_locale;
            SHIM locale_t newlocale(int mask, const char* loc, locale_t base) { return (locale_t)&_dex_dummy_locale; }
            SHIM void freelocale(locale_t l) {}
            SHIM locale_t uselocale(locale_t l) { return (locale_t)&_dex_dummy_locale; }
            SHIM size_t __ctype_get_mb_cur_max(void) { return 4; }

            static struct lconv _dex_lconv = {
                ".", "", "", "", "", "", "", "", "", "",
                127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127
            };
            SHIM struct lconv* localeconv(void) { return &_dex_lconv; }

            /* ---- _l locale variants: forward to C-locale equivalents ---- */
            SHIM int strcoll_l(const char* a, const char* b, locale_t l) { return strcmp(a, b); }
            SHIM size_t strxfrm_l(char* d, const char* s, size_t n, locale_t l) { return strxfrm(d, s, n); }
            SHIM size_t strftime_l(char* s, size_t m, const char* f, const struct tm* t, locale_t l) { return strftime(s, m, f, t); }
            SHIM long long strtoll_l(const char* s, char** e, int b, locale_t l) { return strtoll(s, e, b); }
            SHIM unsigned long long strtoull_l(const char* s, char** e, int b, locale_t l) { return strtoull(s, e, b); }
            SHIM long double strtold_l(const char* s, char** e, locale_t l) { return (long double)strtod(s, e); }

            SHIM int wcscoll_l(const wchar_t* a, const wchar_t* b, locale_t l) {
                for (; *a && *a == *b; a++, b++) {}
                return *a - *b;
            }
            SHIM size_t wcsxfrm_l(wchar_t* d, const wchar_t* s, size_t n, locale_t l) {
                size_t len = wcslen(s);
                if (n > 0) { wcsncpy(d, s, n); if (len < n) d[len] = 0; }
                return len;
            }
            SHIM wint_t towlower_l(wint_t c, locale_t l) { return towlower(c); }
            SHIM wint_t towupper_l(wint_t c, locale_t l) { return towupper(c); }
            SHIM int iswalpha_l(wint_t c, locale_t l) { return iswalpha(c); }
            SHIM int iswblank_l(wint_t c, locale_t l) { return (c == ' ' || c == '\t'); }
            SHIM int iswcntrl_l(wint_t c, locale_t l) { return iswcntrl(c); }
            SHIM int iswdigit_l(wint_t c, locale_t l) { return iswdigit(c); }
            SHIM int iswlower_l(wint_t c, locale_t l) { return iswlower(c); }
            SHIM int iswprint_l(wint_t c, locale_t l) { return iswprint(c); }
            SHIM int iswpunct_l(wint_t c, locale_t l) { return iswpunct(c); }
            SHIM int iswspace_l(wint_t c, locale_t l) { return iswspace(c); }
            SHIM int iswupper_l(wint_t c, locale_t l) { return iswupper(c); }
            SHIM int iswxdigit_l(wint_t c, locale_t l) { return iswxdigit(c); }

            /* ---- Wide-char / multibyte (API 21+) ---- */
            SHIM int mbtowc(wchar_t* pwc, const char* s, size_t n) {
                if (!s) return 0;
                if (n == 0) return -1;
                unsigned char c = (unsigned char)*s;
                if (c == 0) { if (pwc) *pwc = 0; return 0; }
                if (c < 0x80) { if (pwc) *pwc = c; return 1; }
                if (c < 0xC0) return -1;
                if (c < 0xE0 && n >= 2) { if (pwc) *pwc = ((c & 0x1F) << 6) | (s[1] & 0x3F); return 2; }
                if (c < 0xF0 && n >= 3) { if (pwc) *pwc = ((c & 0x0F) << 12) | ((s[1] & 0x3F) << 6) | (s[2] & 0x3F); return 3; }
                if (c < 0xF8 && n >= 4) { if (pwc) *pwc = ((c & 0x07) << 18) | ((s[1] & 0x3F) << 12) | ((s[2] & 0x3F) << 6) | (s[3] & 0x3F); return 4; }
                return -1;
            }

            SHIM size_t mbsnrtowcs(wchar_t* d, const char** src, size_t nms, size_t len, mbstate_t* ps) {
                const char* s = *src;
                size_t count = 0;
                while (nms > 0 && (d == 0 || count < len)) {
                    wchar_t wc;
                    int r = mbtowc(&wc, s, nms);
                    if (r < 0) { errno = 84; return (size_t)-1; }
                    if (r == 0) { if (d) d[count] = 0; *src = 0; return count; }
                    if (d) d[count] = wc;
                    count++;
                    s += r;
                    nms -= r;
                }
                *src = s;
                return count;
            }

            SHIM size_t wcsnrtombs(char* d, const wchar_t** src, size_t nwc, size_t len, mbstate_t* ps) {
                const wchar_t* s = *src;
                size_t count = 0;
                while (nwc > 0 && (d == 0 || count < len)) {
                    wchar_t wc = *s;
                    if (wc == 0) { if (d) d[count] = 0; *src = 0; return count; }
                    if (wc < 0x80) { if (d) d[count] = (char)wc; count++; }
                    else if (wc < 0x800) {
                        if (d && count + 2 > len) break;
                        if (d) { d[count] = 0xC0 | (wc >> 6); d[count+1] = 0x80 | (wc & 0x3F); }
                        count += 2;
                    } else if (wc < 0x10000) {
                        if (d && count + 3 > len) break;
                        if (d) { d[count] = 0xE0 | (wc >> 12); d[count+1] = 0x80 | ((wc >> 6) & 0x3F); d[count+2] = 0x80 | (wc & 0x3F); }
                        count += 3;
                    } else {
                        if (d && count + 4 > len) break;
                        if (d) { d[count] = 0xF0 | (wc >> 18); d[count+1] = 0x80 | ((wc >> 12) & 0x3F); d[count+2] = 0x80 | ((wc >> 6) & 0x3F); d[count+3] = 0x80 | (wc & 0x3F); }
                        count += 4;
                    }
                    s++;
                    nwc--;
                }
                *src = s;
                return count;
            }

            /* ---- Wide-char strto* (API 21+) ---- */
            SHIM float wcstof(const wchar_t* s, wchar_t** e) { return (float)wcstod(s, e); }
            SHIM long double wcstold(const wchar_t* s, wchar_t** e) { return (long double)wcstod(s, e); }
            SHIM long long wcstoll(const wchar_t* s, wchar_t** e, int b) { return (long long)wcstol(s, e, b); }
            SHIM unsigned long long wcstoull(const wchar_t* s, wchar_t** e, int b) { return (unsigned long long)wcstoul(s, e, b); }

            /* ---- Fortified libc (API 17+) ---- */
            SHIM void* __memcpy_chk(void* d, const void* s, size_t n, size_t dl) { return memcpy(d, s, n); }
            SHIM void* __memmove_chk(void* d, const void* s, size_t n, size_t dl) { return memmove(d, s, n); }
            SHIM void* __memset_chk(void* d, int c, size_t n, size_t dl) { return memset(d, c, n); }
            SHIM char* __strcpy_chk(char* d, const char* s, size_t dl) { return strcpy(d, s); }
            SHIM char* __strncpy_chk(char* d, const char* s, size_t n, size_t dl) { return strncpy(d, s, n); }
            SHIM char* __strncpy_chk2(char* d, const char* s, size_t n, size_t dl, size_t sl) { return strncpy(d, s, n); }
            SHIM char* __strcat_chk(char* d, const char* s, size_t dl) { return strcat(d, s); }
            SHIM char* __strncat_chk(char* d, const char* s, size_t n, size_t dl) { return strncat(d, s, n); }
            SHIM size_t __strlen_chk(const char* s, size_t sl) { return strlen(s); }
            SHIM char* __strchr_chk(const char* s, int c, size_t sl) { return strchr(s, c); }
            SHIM char* __strrchr_chk(const char* s, int c, size_t sl) { return strrchr(s, c); }
            SHIM int __snprintf_chk(char* b, size_t m, int f, size_t sl, const char* fmt, ...) {
                va_list ap; va_start(ap, fmt); int r = vsnprintf(b, m, fmt, ap); va_end(ap); return r;
            }
            SHIM int __vsnprintf_chk(char* b, size_t m, int f, size_t sl, const char* fmt, va_list ap) {
                return vsnprintf(b, m, fmt, ap);
            }
            SHIM int __sprintf_chk(char* b, int f, size_t sl, const char* fmt, ...) {
                va_list ap; va_start(ap, fmt); int r = vsprintf(b, fmt, ap); va_end(ap); return r;
            }
            SHIM int __vsprintf_chk(char* b, int f, size_t sl, const char* fmt, va_list ap) {
                return vsprintf(b, fmt, ap);
            }
            SHIM void __FD_SET_chk(int fd, fd_set* set, size_t ss) { if (fd >= 0 && fd < FD_SETSIZE) FD_SET(fd, set); }
            SHIM int __FD_ISSET_chk(int fd, const fd_set* set, size_t ss) { return (fd >= 0 && fd < FD_SETSIZE) ? FD_ISSET(fd, set) : 0; }
            SHIM void __FD_CLR_chk(int fd, fd_set* set, size_t ss) { if (fd >= 0 && fd < FD_SETSIZE) FD_CLR(fd, set); }

            /* ---- API 18+: stpcpy/stpncpy ---- */
            SHIM char* stpcpy(char* d, const char* s) { while ((*d = *s)) { d++; s++; } return d; }
            SHIM char* stpncpy(char* d, const char* s, size_t n) {
                for (; n && (*d = *s); n--, d++, s++) {}
                char* e = d; for (; n; n--, d++) *d = '\0'; return e;
            }
            ]==])
            if(NOT TARGET _dexorcist_shim)
                add_library(_dexorcist_shim STATIC "${'$'}{CMAKE_CURRENT_BINARY_DIR}/_dexorcist_shim.c")
                target_compile_options(_dexorcist_shim PRIVATE -w -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0)
                target_link_libraries(_dexorcist_shim PRIVATE dl)
                link_libraries(_dexorcist_shim)
                if(DEXORCIST_LEGACY_X86 AND CMAKE_ANDROID_ARCH_ABI STREQUAL "x86")
                    add_compile_options(-mstack-protector-guard=global)
                endif()
            endif()
        """.trimIndent() + "\n")
    }
}
