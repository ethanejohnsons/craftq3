/* Authored registration-boundary observer. Only renderer header metadata is used.
 * Compile the metadata adapter into a separate copy of the unchanged renderer objects;
 * no renderer routine is replaced and no graphics device is initialized. */
#include "renderergl1/tr_local.h"

#ifdef ORACLE_METADATA_ADAPTER
__attribute__((visibility("default"))) void ShaderOracleDefault(void) {
    static shader_t fallback;
    fallback.index = 0;
    fallback.defaultShader = qtrue;
    tr.defaultShader = &fallback;
}
#else
#include <dlfcn.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void QDECL print(int level, const char *format, ...) {
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
}
static void QDECL Q_NO_RETURN fatal(int level, const char *format, ...) {
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
    exit(3);
}
static void invalid_pointer(int signal_number) {
    static const char text[] = "NATIVE_INVALID_POINTER\n";
    write(STDERR_FILENO, text, sizeof(text) - 1);
    _Exit(128 + signal_number);
}
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    signal(SIGSEGV, invalid_pointer);
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 3; }
    refexport_t *(*get)(int, refimport_t *) = dlsym(library, "GetRefAPI");
    void (*prepare)(void) = dlsym(library, "ShaderOracleDefault");
    if (!get || !prepare) return 4;
    refimport_t imports = {0};
    imports.Printf = print;
    imports.Error = fatal;
    refexport_t *api = get(REF_API_VERSION, &imports);
    if (!api) return 5;
    prepare();
    char storage[1025];
    const char *name;
    if (!strcmp(argv[2], "null")) name = NULL;
    else if (!strcmp(argv[2], "empty")) name = "";
    else {
        int length;
        if (sscanf(argv[2], "length%d", &length) != 1 || length < 64 || length > 1024) return 2;
        memset(storage, 'x', length);
        storage[length] = 0;
        name = storage;
    }
    printf("SHADER %d\n", api->RegisterShader(name));
    printf("NOMIP %d\n", api->RegisterShaderNoMip(name));
    return 0;
}
#endif
