#
# Copyright 2014-2019 Real Logic Ltd.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if(MSVC AND "${CMAKE_SYSTEM_NAME}" MATCHES "Windows")
	set(AERON_LIB_WINSOCK_LIBS wsock32 ws2_32 Iphlpapi)	
endif()

include_directories(${AERON_DRIVER_SOURCE_PATH})

set(TEST_HEADERS aeron_driver_conductor_test.h EmbeddedMediaDriver.h)

function(aeron_driver_test name file)
    add_executable(${name} ${file} ${TEST_HEADERS})
    target_link_libraries(${name} aeron_client aeron_driver ${GMOCK_LIBS} ${CMAKE_THREAD_LIBS_INIT} ${AERON_LIB_WINSOCK_LIBS})
    target_compile_definitions(${name} PUBLIC "_SILENCE_TR1_NAMESPACE_DEPRECATION_WARNING")
    add_dependencies(${name} gmock)
    add_test(NAME ${name} COMMAND ${name})
endfunction()

aeron_driver_test(spsc_rb_test aeron_spsc_rb_test.cpp)
aeron_driver_test(mpsc_rb_test aeron_mpsc_rb_test.cpp)
aeron_driver_test(broadcast_transmitter_test aeron_broadcast_transmitter_test.cpp)
aeron_driver_test(counters_manager_test aeron_counters_manager_test.cpp)
aeron_driver_test(distinct_error_log_test aeron_distinct_error_log_test.cpp)
aeron_driver_test(driver_conductor_ipc_test aeron_driver_conductor_ipc_test.cpp)
aeron_driver_test(driver_conductor_network_test aeron_driver_conductor_network_test.cpp)
aeron_driver_test(driver_conductor_spy_test aeron_driver_conductor_spy_test.cpp)
aeron_driver_test(driver_conductor_counter_test aeron_driver_conductor_counter_test.cpp)
aeron_driver_test(spsc_queue_test aeron_spsc_concurrent_array_queue_test.cpp)
aeron_driver_test(mpsc_queue_test aeron_mpsc_concurrent_array_queue_test.cpp)
aeron_driver_test(uri_test aeron_uri_test.cpp)
aeron_driver_test(udp_channel_test aeron_udp_channel_test.cpp)
aeron_driver_test(int64_to_ptr_hash_map_test collections/aeron_int64_to_ptr_hash_masp_test.cpp)
aeron_driver_test(str_to_ptr_hash_map_test collections/aeron_str_to_ptr_hash_map_test.cpp)
aeron_driver_test(bit_set_test collections/aeron_bit_set_test.cpp)
aeron_driver_test(bitutil_test util/aeron_bitutil_test.cpp)
aeron_driver_test(math_test util/aeron_math_test.cpp)
aeron_driver_test(term_scanner_test aeron_term_scanner_test.cpp)
aeron_driver_test(loss_detector_test aeron_loss_detector_test.cpp)
aeron_driver_test(retransmit_handler_test aeron_retransmit_handler_test.cpp)
aeron_driver_test(loss_reporter_test aeron_loss_reporter_test.cpp)
aeron_driver_test(logbuffer_unblocker aeron_logbuffer_unblocker_test.cpp)
aeron_driver_test(term_gap_filler_test aeron_term_gap_filler_test.cpp)
aeron_driver_test(parse_util_test aeron_parse_util_test.cpp)
aeron_driver_test(properties_test aeron_properties_test.cpp)
aeron_driver_test(driver_configuration_test aeron_driver_configuration_test.cpp)
aeron_driver_test(system_test aeron_system_test.cpp)
