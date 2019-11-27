/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef AERON_TERMINATION_VALIDATOR_H
#define AERON_TERMINATION_VALIDATOR_H

#include "aeron_driver_common.h"
#include "aeronmd.h"

bool aeron_driver_termination_validator_default_allow(void *state, uint8_t *token_buffer, int32_t token_length);
bool aeron_driver_termination_validator_default_deny(void *state, uint8_t *token_buffer, int32_t token_length);

aeron_driver_termination_validator_func_t aeron_driver_termination_validator_load(const char *validator_name);

#endif //AERON_TERMINATION_VALIDATOR_H