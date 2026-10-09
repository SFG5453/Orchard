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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"

#include <string>

// LiteRT C API resolved at runtime, so a missing runtime disables translation instead of the app.
struct LiteRtApi {
#define ORCHARD_LITERT_FUNCTIONS(X)                                                                \
  X(LiteRtCreateEnvironment)                                                                       \
  X(LiteRtDestroyEnvironment)                                                                      \
  X(LiteRtCreateModelFromFile)                                                                     \
  X(LiteRtDestroyModel)                                                                            \
  X(LiteRtCreateOptions)                                                                           \
  X(LiteRtDestroyOptions)                                                                          \
  X(LiteRtSetOptionsHardwareAccelerators)                                                          \
  X(LiteRtCreateOpaqueOptions)                                                                     \
  X(LiteRtAddOpaqueOptions)                                                                        \
  X(LiteRtCreateCompiledModel)                                                                     \
  X(LiteRtDestroyCompiledModel)                                                                    \
  X(LiteRtRunCompiledModel)                                                                        \
  X(LiteRtGetNumModelSignatures)                                                                   \
  X(LiteRtGetModelSignature)                                                                       \
  X(LiteRtGetSignatureKey)                                                                         \
  X(LiteRtGetNumSignatureInputs)                                                                   \
  X(LiteRtGetSignatureInputName)                                                                   \
  X(LiteRtGetSignatureInputTensor)                                                                 \
  X(LiteRtGetNumSignatureOutputs)                                                                  \
  X(LiteRtGetSignatureOutputName)                                                                  \
  X(LiteRtGetSignatureOutputTensor)                                                                \
  X(LiteRtGetRankedTensorType)                                                                     \
  X(LiteRtCreateTensorBufferFromHostMemory)                                                        \
  X(LiteRtDestroyTensorBuffer)

#define ORCHARD_LITERT_MEMBER(name) decltype(&::name) name{nullptr};
  ORCHARD_LITERT_FUNCTIONS(ORCHARD_LITERT_MEMBER)
#undef ORCHARD_LITERT_MEMBER

  // Process-wide table loaded from path on first call; later paths are ignored.
  // Null with error set when the runtime is missing or too old.
  static const LiteRtApi *get(const std::string &path, std::string *error = nullptr);
};
