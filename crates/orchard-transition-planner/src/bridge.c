/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// The planner gets JavaScript built-ins, but no files, network, or host objects.
#include "quickjs.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    const char *const *names;
    const char *const *sources;
    size_t count;
    clock_t started;
} PlannerSources;

static int interrupted(JSRuntime *runtime, void *opaque) {
    (void)runtime;
    PlannerSources *sources = opaque;
    return (double)(clock() - sources->started) / CLOCKS_PER_SEC > 5.0;
}

static JSModuleDef *load_module(JSContext *ctx, const char *name, void *opaque) {
    PlannerSources *sources = opaque;
    for (size_t i = 0; i < sources->count; ++i) {
        if (strcmp(name, sources->names[i])) continue;
        JSValue module = JS_Eval(ctx, sources->sources[i], strlen(sources->sources[i]),
                                 name, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
        if (JS_IsException(module)) return NULL;
        JSModuleDef *definition = JS_VALUE_GET_PTR(module);
        JS_FreeValue(ctx, module);
        return definition;
    }
    JS_ThrowReferenceError(ctx, "Planner module is not embedded: %s", name);
    return NULL;
}

// A fresh bounded context per pair prevents stale analyses from becoming DJ folklore.
char *orchard_planner_invoke(const char *method, const char *input,
                            const char *const *names, const char *const *sources,
                            size_t count, int *success) {
    *success = 0;
    JSRuntime *runtime = JS_NewRuntime();
    if (!runtime) return NULL;
    JS_SetMemoryLimit(runtime, 128 * 1024 * 1024);
    JS_SetMaxStackSize(runtime, 2 * 1024 * 1024);
    PlannerSources embedded = {names, sources, count, clock()};
    JS_SetModuleLoaderFunc(runtime, NULL, load_module, &embedded);
    JS_SetInterruptHandler(runtime, interrupted, &embedded);
    JSContext *ctx = JS_NewContext(runtime);
    if (!ctx) { JS_FreeRuntime(runtime); return NULL; }
    const char *entry = "import { invoke } from './crates/orchard-transition-planner/js/index.js';"
                        "globalThis.__orchardInvoke = invoke;";
    JSValue value = JS_Eval(ctx, entry, strlen(entry), "entry.js", JS_EVAL_TYPE_MODULE);
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue function = JS_UNDEFINED;
    JSValue args[2] = {JS_UNDEFINED, JS_UNDEFINED};
    if (!JS_IsException(value)) {
        JS_FreeValue(ctx, value);
        function = JS_GetPropertyStr(ctx, global, "__orchardInvoke");
        args[0] = JS_NewString(ctx, method);
        args[1] = JS_NewString(ctx, input);
        value = JS_Call(ctx, function, JS_UNDEFINED, 2, args);
    }
    if (JS_IsException(value)) {
        JS_FreeValue(ctx, value);
        value = JS_GetException(ctx);
    } else {
        *success = 1;
    }
    const char *text = JS_ToCString(ctx, value);
    char *result = text ? malloc(strlen(text) + 1) : NULL;
    if (result) strcpy(result, text);
    if (text) JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, value);
    JS_FreeValue(ctx, args[0]); JS_FreeValue(ctx, args[1]);
    JS_FreeValue(ctx, function); JS_FreeValue(ctx, global);
    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);
    return result;
}

void orchard_planner_free(char *value) { free(value); }

// A queue sort makes many related pair decisions. Keep one bounded QuickJS
// context for that sort, then discard it; the JS entry caches only two.
typedef struct {
    JSRuntime *runtime;
    JSContext *ctx;
    JSValue global;
    JSValue function;
    PlannerSources embedded;
} OrchardPlannerSession;

void orchard_planner_session_free(OrchardPlannerSession *session) {
    if (!session) return;
    if (session->ctx) {
        JS_FreeValue(session->ctx, session->function);
        JS_FreeValue(session->ctx, session->global);
        JS_FreeContext(session->ctx);
    }
    if (session->runtime) JS_FreeRuntime(session->runtime);
    free(session);
}

OrchardPlannerSession *orchard_planner_session_new(const char *const *names,
        const char *const *sources, size_t count) {
    OrchardPlannerSession *session = calloc(1, sizeof(*session));
    if (!session) return NULL;
    session->runtime = JS_NewRuntime();
    if (!session->runtime) { orchard_planner_session_free(session); return NULL; }
    JS_SetMemoryLimit(session->runtime, 128 * 1024 * 1024);
    JS_SetMaxStackSize(session->runtime, 2 * 1024 * 1024);
    session->embedded = (PlannerSources){names, sources, count, clock()};
    JS_SetModuleLoaderFunc(session->runtime, NULL, load_module, &session->embedded);
    JS_SetInterruptHandler(session->runtime, interrupted, &session->embedded);
    session->ctx = JS_NewContext(session->runtime);
    if (!session->ctx) { orchard_planner_session_free(session); return NULL; }
    session->global = JS_UNDEFINED;
    session->function = JS_UNDEFINED;
    const char *entry = "import { invoke } from './crates/orchard-transition-planner/js/index.js';"
                        "globalThis.__orchardInvoke = invoke;";
    JSValue value = JS_Eval(session->ctx, entry, strlen(entry), "entry.js", JS_EVAL_TYPE_MODULE);
    if (JS_IsException(value)) {
        JS_FreeValue(session->ctx, value);
        orchard_planner_session_free(session);
        return NULL;
    }
    JS_FreeValue(session->ctx, value);
    session->global = JS_GetGlobalObject(session->ctx);
    session->function = JS_GetPropertyStr(session->ctx, session->global, "__orchardInvoke");
    if (!JS_IsFunction(session->ctx, session->function)) {
        orchard_planner_session_free(session);
        return NULL;
    }
    return session;
}

char *orchard_planner_session_invoke(OrchardPlannerSession *session,
        const char *method, const char *input, int *success) {
    *success = 0;
    if (!session) return NULL;
    session->embedded.started = clock();
    JSValue args[2] = {JS_NewString(session->ctx, method), JS_NewString(session->ctx, input)};
    JSValue value = JS_Call(session->ctx, session->function, JS_UNDEFINED, 2, args);
    if (JS_IsException(value)) {
        JS_FreeValue(session->ctx, value);
        value = JS_GetException(session->ctx);
    } else {
        *success = 1;
    }
    const char *text = JS_ToCString(session->ctx, value);
    char *result = text ? malloc(strlen(text) + 1) : NULL;
    if (result) strcpy(result, text);
    if (text) JS_FreeCString(session->ctx, text);
    JS_FreeValue(session->ctx, value);
    JS_FreeValue(session->ctx, args[0]); JS_FreeValue(session->ctx, args[1]);
    return result;
}
