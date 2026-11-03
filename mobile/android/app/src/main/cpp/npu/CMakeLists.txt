add_library(orchard_npu SHARED npu.cpp neuron.cpp util.cpp)
set_target_properties(orchard_npu PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(orchard_npu PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(orchard_npu PRIVATE dl log)
