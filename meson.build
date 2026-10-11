project('Orchard', ['c', 'cpp'], version: '1.0.0-canary.8', meson_version: '>=1.12.0',
  subproject_dir: 'third_party', default_options: ['cpp_std=c++20', 'b_lto=false'])

cmake = import('cmake')
qt6 = import('qt6')

# Upstream stays on CMake; Meson imports its checked-out submodule targets.
quickjs_options = cmake.subproject_options()
quickjs_options.add_cmake_defines({
  'QJS_ENABLE_INSTALL': false,
  'QJS_BUILD_EXAMPLES': false,
  'QJS_BUILD_CLI_STATIC': false,
  'QJS_BUILD_LIBC': false,
})
quickjs = cmake.subproject('quickjs', options: quickjs_options)
qjs_dep = quickjs.dependency('qjs')

mbedtls_options = cmake.subproject_options()
mbedtls_options.add_cmake_defines({
  'CMAKE_POLICY_VERSION_MINIMUM': '3.10',
  'ENABLE_PROGRAMS': false,
  'ENABLE_TESTING': false,
  'MBEDTLS_FATAL_WARNINGS': false,
  'USE_STATIC_MBEDTLS_LIBRARY': true,
  'USE_SHARED_MBEDTLS_LIBRARY': false,
  'DISABLE_PACKAGE_CONFIG_AND_INSTALL': true,
  'MBEDTLS_USER_CONFIG_FILE': meson.project_source_root() / 'core/native/connect/mbedtls_user_config.h',
})
mbedtls = cmake.subproject('mbedtls', options: mbedtls_options)
mbedtls_dep = mbedtls.dependency('mbedtls')
mbedx509_dep = mbedtls.dependency('mbedx509')
mbedcrypto_dep = mbedtls.dependency('mbedcrypto')

# The CMake subprojects are configured independently. Publish the paths of
# Meson's Mbed TLS outputs as a local CMake package for libdatachannel.
# CMake hears Windows backslashes as escape codes; keep the paths quiet.
mbedtls_config = configuration_data()
mbedtls_config.set('MBEDTLS_ARCHIVE', mbedtls.target('mbedtls').full_path().replace('\\', '/'))
mbedtls_config.set('MBEDX509_ARCHIVE', mbedtls.target('mbedx509').full_path().replace('\\', '/'))
mbedtls_config.set('MBEDCRYPTO_ARCHIVE', mbedtls.target('mbedcrypto').full_path().replace('\\', '/'))
mbedtls_config.set('MBEDTLS_INCLUDE', (meson.project_source_root() / 'third_party/mbedtls/include').replace('\\', '/'))
mbedtls_config.set('MBEDTLS_USER_CONFIG', (meson.project_source_root() / 'core/native/connect/mbedtls_user_config.h').replace('\\', '/'))
mbedtls_config.set('MBEDTLS_VERSION', '3.6.7')
configure_file(input: 'meson/MbedTLSConfig.cmake.in', output: 'MbedTLSConfig.cmake', configuration: mbedtls_config)
configure_file(input: 'meson/MbedTLSConfigVersion.cmake.in', output: 'MbedTLSConfigVersion.cmake', configuration: mbedtls_config)

datachannel_options = cmake.subproject_options()
mbedtls_include_flag = '-I' + meson.project_source_root() / 'third_party/mbedtls/include'
if host_machine.system() == 'windows'
  mbedtls_include_flag = '/I' + meson.project_source_root() / 'third_party/mbedtls/include'
endif
datachannel_options.add_cmake_defines({
  'BUILD_SHARED_LIBS': false,
  'USE_MBEDTLS': true,
  'CMAKE_FIND_PACKAGE_PREFER_CONFIG': true,
  'MbedTLS_DIR': meson.current_build_dir(),
  'PREFER_SYSTEM_LIB': false,
  'NO_MEDIA': true,
  'NO_WEBSOCKET': false,
  'NO_EXAMPLES': true,
  'NO_TESTS': true,
  'CMAKE_CXX_FLAGS': mbedtls_include_flag,
})
datachannel = cmake.subproject('libdatachannel', options: datachannel_options)
datachannel_dep = datachannel.dependency('datachannel-static')

json_options = cmake.subproject_options()
json_options.add_cmake_defines({'JSON_BuildTests': false, 'JSON_Install': false})
json = cmake.subproject('json', options: json_options)
json_dep = json.dependency('nlohmann_json')

subdir('meson')

cpp = meson.get_compiler('cpp')
discord_sdk_root = meson.project_source_root() / 'vendor/discord_social_sdk'
if host_machine.system() == 'windows'
  discord_sdk_libdir = discord_sdk_root / 'lib/windows'
  configure_file(input: discord_sdk_libdir / 'discord_partner_sdk.dll',
    output: 'discord_partner_sdk.dll', copy: true)
elif host_machine.system() == 'darwin'
  discord_sdk_libdir = discord_sdk_root / 'lib/macos'
else
  discord_sdk_libdir = discord_sdk_root / 'lib/linux'
  configure_file(input: discord_sdk_libdir / 'libdiscord_partner_sdk.so',
    output: 'libdiscord_partner_sdk.so', copy: true)
endif
discord_sdk_dep = declare_dependency(
  include_directories: include_directories('vendor/discord_social_sdk/include'),
  dependencies: [cpp.find_library('discord_partner_sdk', dirs: discord_sdk_libdir)])
# Lyric translation loads LiteRT at runtime; the build only needs its C headers.
litert_libdir = 'vendor/litert/lib'
if host_machine.system() == 'windows'
  configure_file(input: litert_libdir / 'windows/libLiteRt.dll', output: 'libLiteRt.dll', copy: true)
elif host_machine.system() == 'darwin'
  configure_file(input: litert_libdir / 'macos/libLiteRt.dylib', output: 'libLiteRt.dylib', copy: true)
elif host_machine.cpu_family() == 'x86_64'
  configure_file(input: litert_libdir / 'linux/libLiteRt.so', output: 'libLiteRt.so', copy: true)
endif
qt_modules = ['Core', 'Gui', 'Widgets', 'Qml', 'Quick', 'QuickControls2',
  'Network', 'Multimedia', 'Concurrent', 'Sql', 'WebSockets']
if meson.is_cross_build() and host_machine.system() == 'windows'
  target_qt_deps = []
  foreach module : qt_modules
    target_qt_deps += dependency('Qt6' + module, method: 'cmake',
      modules: ['Qt6::' + module])
  endforeach
  qt_dep = declare_dependency(dependencies: target_qt_deps)
  qt_web_dep = declare_dependency(dependencies: [
    dependency('Qt6WebView', method: 'cmake', modules: ['Qt6::WebView']),
    dependency('Qt6WebViewQuick', method: 'cmake', modules: ['Qt6::WebViewQuick']),
  ])
  qt_webengine_dep = declare_dependency(dependencies: [
    dependency('Qt6WebEngineCore', method: 'cmake', modules: ['Qt6::WebEngineCore']),
    dependency('Qt6WebEngineQuick', method: 'cmake', modules: ['Qt6::WebEngineQuick']),
  ])
  qt_entry_dep = dependency('Qt6Core', method: 'cmake',
    modules: ['Qt6::EntryPointPrivate'])
else
  qt_dep = dependency('qt6', version: '>=6.8', modules: qt_modules, main: true)
  qt_web_dep = dependency('qt6', version: '>=6.8', modules: ['WebView', 'WebViewQuick'])
  if host_machine.system() == 'windows' or host_machine.system() == 'linux'
    qt_webengine_dep = dependency('qt6', version: '>=6.8',
      modules: ['WebEngineCore', 'WebEngineQuick'])
  endif
  qt_entry_dep = []
endif
qt_gui_private = dependency('Qt6GuiPrivate', method: 'cmake', modules: ['Qt6::GuiPrivate'])
thread_dep = dependency('threads')
platform_deps = [thread_dep]
# DBus (Linux) and IOKit (macOS) back the screen wake lock.
if host_machine.system() == 'linux'
  platform_deps += [cpp.find_library('dl'), cpp.find_library('m'), dependency('qt6', modules: ['DBus'])]
elif host_machine.system() == 'windows'
  platform_deps += [cpp.find_library('dbghelp'), cpp.find_library('shell32')]
elif host_machine.system() == 'darwin'
  platform_deps += [dependency('appleframeworks', modules: ['IOKit', 'CoreFoundation'])]
endif

python = find_program('python3')
npm = find_program('npm')
# qmake sometimes omits tool paths; "." is a lousy treasure map for Meson.
qt_core = dependency('qt6', modules: ['Core'])
qt_tool_dirs = []
foreach variable : ['bindir', 'libexecdir']
  tool_dir = qt_core.get_variable(pkgconfig: variable, default_value: '')
  if tool_dir != ''
    qt_tool_dirs += [tool_dir]
  endif
endforeach
rcc = get_option('host_rcc') == '' ? find_program('rcc', dirs: qt_tool_dirs) : find_program(get_option('host_rcc'))
qsb = get_option('host_qsb') == '' ? find_program('qsb', dirs: qt_tool_dirs) : find_program(get_option('host_qsb'))
cargo = find_program('cargo')
source_root = meson.project_source_root()
cargo_target_dir = meson.current_build_dir() / 'cargo-target'
rust_target = get_option('orchard_rust_target')
if meson.is_cross_build() and host_machine.system() == 'windows' and rust_target == ''
  rust_target = 'x86_64-pc-windows-gnullvm'
endif
rust_profile = get_option('buildtype') == 'debug' ? 'debug' : 'release'
rust_output = 'liborchard_core_ffi.a'
if host_machine.system() == 'windows' and cpp.get_id() == 'msvc'
  rust_output = 'orchard_core_ffi.lib'
endif
rust_archive = custom_target('orchard Rust core',
  input: files(rust_sources),
  output: rust_output,
  command: [python, files('scripts/meson-cargo-build.py'),
    '--package', 'orchard-core-ffi', '--target-dir', cargo_target_dir,
    '--target', rust_target, '--profile', rust_profile, '--output', '@OUTPUT@'],
)
rust_platform_deps = []
if host_machine.system() == 'windows'
  rust_platform_deps += [cpp.find_library('ntdll'), cpp.find_library('bcrypt')]
endif
rust_dep = declare_dependency(link_with: rust_archive,
  dependencies: rust_platform_deps)

# libdatachannel consumes all three bundled crypto archives through the local
# CMake package above. Explicit Meson dependencies also order their builds.
connect_inc = include_directories('core/native')
connect_lib = static_library('orchard_connect', files(connect_sources),
  include_directories: connect_inc,
  dependencies: [datachannel_dep, mbedtls_dep, mbedx509_dep, mbedcrypto_dep, json_dep],
  override_options: ['cpp_std=c++17'], pic: true)
connect_dep = declare_dependency(link_with: connect_lib,
  include_directories: connect_inc,
  dependencies: [datachannel_dep, mbedtls_dep, mbedx509_dep, mbedcrypto_dep, json_dep])

# Meson's CMake translator invents an empty moc byproduct for qtkeychain.
# The empty file has career ambitions, but Meson quite reasonably objects.
# Build this one submodule with its upstream CMake graph, then import its archive.
keychain_lib_name = 'libqt6keychain.a'
if host_machine.system() == 'windows' and cpp.get_id() == 'msvc'
  keychain_lib_name = 'qt6keychain.lib'
endif
keychain_archive = custom_target('qtkeychain archive',
  input: files('meson/meson.build'),
  output: keychain_lib_name,
  command: [python, files('scripts/meson-build-qtkeychain.py'),
    '--source', source_root / 'third_party/qtkeychain',
    '--build-dir', meson.current_build_dir() / 'qtkeychain-cmake',
    '--output', '@OUTPUT@', '--build-type', get_option('buildtype') == 'debug' ? 'Debug' : 'Release',
    '--toolchain', meson.current_build_dir() / 'third_party/quickjs/__CMake_build/CMakeMesonToolchainFile.cmake'],
  build_always_stale: true)
keychain_deps = [qt_dep]
if host_machine.system() == 'linux'
  keychain_deps += [dependency('qt6', modules: ['DBus']), dependency('libsecret-1')]
elif host_machine.system() == 'windows'
  keychain_deps += [cpp.find_library('crypt32')]
elif host_machine.system() == 'darwin'
  keychain_deps += [dependency('appleframeworks', modules: ['Foundation', 'Security'])]
endif
keychain_dep = declare_dependency(link_with: keychain_archive,
  compile_args: ['-I' + source_root / 'third_party/qtkeychain', '-DQTKEYCHAIN_NO_EXPORT'],
  dependencies: keychain_deps)

qjsc = get_option('host_qjsc') == '' ? quickjs.target('qjsc') : find_program(get_option('host_qjsc'))
if meson.is_cross_build() and get_option('host_qjsc') == ''
  error('Set -Dhost_qjsc=/path/to/qjsc built from third_party/quickjs for cross compilation')
endif
youtube_bundle = custom_target('YouTube provider bundle',
  input: files(youtube_sources + ['providers/youtube/package.json',
    'providers/youtube/package-lock.json']),
  output: 'youtube-provider.js',
  command: [npm, '--prefix', source_root / 'providers/youtube', 'run',
    'build:runtime', '--', '--outfile=' + meson.current_build_dir() / 'youtube-provider.js'])
po_bundle = custom_target('YouTube PO minter bundle',
  input: files(youtube_sources + ['providers/youtube/scripts/buildPoMinter.mjs',
    'providers/youtube/scripts/poNodeGlobals.js', 'providers/youtube/package.json',
    'providers/youtube/package-lock.json']),
  output: ['youtube-po-minter.js', 'youtube-po-minter-licenses.txt'],
  command: [npm, '--prefix', source_root / 'providers/youtube', 'run',
    'build:po-minter', '--', '--outfile=' + meson.current_build_dir() / 'youtube-po-minter.js'])
qobuz_bundle = custom_target('Qobuz provider bundle',
  input: files(qobuz_sources + ['providers/youtube/package-lock.json']),
  output: 'qobuz-provider.js',
  command: [npm, '--prefix', source_root / 'providers/youtube', 'exec', '--offline',
    '--', 'esbuild', source_root / 'providers/qobuz/src/runtime.js', '--bundle',
    '--platform=browser', '--format=iife', '--target=es2023',
    '--legal-comments=none', '--outfile=' + meson.current_build_dir() / 'qobuz-provider.js'])
youtube_bytecode = custom_target('YouTube provider bytecode', input: youtube_bundle,
  output: 'youtube-provider.qjc',
  command: [qjsc, '-b', '-C', '-n', 'youtube-provider.js', '-o', '@OUTPUT@', '@INPUT@'])
po_bytecode = custom_target('YouTube PO minter bytecode', input: po_bundle[0],
  output: 'youtube-po-minter.qjc',
  command: [qjsc, '-b', '-C', '-s', '-n', 'youtube-po-minter.js', '-o', '@OUTPUT@', '@INPUT@'])
qobuz_bytecode = custom_target('Qobuz provider bytecode', input: qobuz_bundle,
  output: 'qobuz-provider.qjc',
  command: [qjsc, '-b', '-C', '-n', 'qobuz-provider.js', '-o', '@OUTPUT@', '@INPUT@'])

provider_resources = custom_target('provider resources',
  input: [youtube_bytecode, po_bytecode, po_bundle[1], qobuz_bytecode],
  output: 'qrc_provider_resources.cpp',
  command: [python, files('scripts/meson-rcc.py'), '--rcc', rcc,
    '--name', 'provider_resources', '--prefix', '/providers',
    '--output', '@OUTPUT@', '@INPUT@'])
auth_provider_resources = custom_target('auth provider resources',
  input: [youtube_bytecode, po_bytecode, po_bundle[1]],
  output: 'qrc_auth_provider_resources.cpp',
  command: [python, files('scripts/meson-rcc.py'), '--rcc', rcc,
    '--name', 'auth_provider_resources', '--prefix', '/providers',
    '--output', '@OUTPUT@', '@INPUT@'])

shader_resources = []
foreach shader : shader_sources
  name = shader.split('/')[-1]
  shader_resources += custom_target('shader ' + name,
    input: files(shader), output: name + '.qsb',
    command: [qsb, '--glsl', '100 es,120,150', '--hlsl', '50', '--msl', '12', '-o', '@OUTPUT@', '@INPUT@'])
endforeach
shaders_cpp = custom_target('shader resources', input: shader_resources,
  output: 'qrc_shader_resources.cpp',
  command: [python, files('scripts/meson-rcc.py'), '--rcc', rcc,
    '--name', 'shader_resources', '--prefix', '/shaders',
    '--output', '@OUTPUT@', '@INPUT@'])

rcc_args = ['--compress-algo', 'zlib']
licenses_qrc = qt6.compile_resources(sources: files('meson/licenses.qrc'), extra_args: rcc_args)
docs_qrc = qt6.compile_resources(sources: files('meson/app_docs.qrc'), extra_args: rcc_args)
release_notes_qrc = qt6.compile_resources(sources: files('meson/release_notes.qrc'), extra_args: rcc_args)
assets_qrc = qt6.compile_resources(sources: files('meson/qml_assets.qrc'), extra_args: rcc_args)
qml_files_qrc = qt6.compile_resources(sources: files('meson/qml_files.qrc'), extra_args: rcc_args)
auth_qrc = qt6.compile_resources(sources: files('meson/auth_qml.qrc'), extra_args: rcc_args)

app_inc = include_directories('app/src', 'app/src/auth',
  'app/src/appearance', 'core/native', 'vendor/litert/include')
app_dependencies = [qt_dep, qt_gui_private, connect_dep, rust_dep, discord_sdk_dep,
  qjs_dep, keychain_dep] + platform_deps
app_args = ['-DORCHARD_VERSION="' + meson.project_version() + '"']
if host_machine.system() == 'windows' or host_machine.system() == 'linux'
  app_args += ['-DORCHARD_NATIVE_COOKIE_AUTH']
endif
app_moc = qt6.compile_moc(headers: app_moc_headers,
  sources: ['app/src/providers/runtime/provider_runtime.cpp'],
  include_directories: app_inc, dependencies: app_dependencies,
  extra_args: app_args)
app_lib = static_library('orchard_app', files(app_sources) + app_moc,
  include_directories: app_inc, dependencies: app_dependencies,
  cpp_args: app_args, pic: true)
app_dep = declare_dependency(link_with: app_lib, include_directories: app_inc,
  dependencies: app_dependencies, compile_args: app_args)

qml_type_headers = [
  'app/src/appearance/artwork_palette.h',
  'app/src/appearance/kawarp_background.h',
  'app/src/appearance/liquid_glass.h',
]
# Meson's Qt helper puts every source at the module root. Thin wrappers keep
# each real QML file beside the relative imports it expects.
qml_wrappers = []
qml_declarations = []
foreach qml_source : qml_sources
  qml_file = qml_source.split('/')[-1]
  # Designer forms (Foo.ui.qml) are exported as the plain type Foo.
  qml_name = qml_file.replace('.ui.qml', '.qml')
  qml_conf = configuration_data()
  qml_conf.set('QML_DIRECTORY', qml_source.replace('/' + qml_file, ''))
  qml_conf.set('QML_TYPE', qml_name.split('.')[0])
  qml_wrappers += configure_file(input: 'meson/qml-wrapper.qml.in',
    output: qml_name, configuration: qml_conf)
  qml_declarations += qml_name.split('.')[0] + ' 1.0 ' + qml_name
endforeach
foreach singleton : qml_singletons
  qml_name = singleton.split('/')[-1]
  qml_declarations += 'singleton ' + qml_name.split('.')[0] + ' 1.0 ' + qml_name
endforeach
# Resource URLs need forward slashes even when Meson runs on Windows.
qmldir_conf = configuration_data()
qmldir_conf.set('QML_DECLARATIONS', '\n'.join(qml_declarations))
qmldir_file = configure_file(input: 'meson/qmldir.in',
  output: 'qmldir', configuration: qmldir_conf)
qmldir_resources = custom_target('Orchard module manifest', input: qmldir_file,
  output: 'qrc_orchard_manifest.cpp',
  command: [python, files('scripts/meson-rcc.py'), '--rcc', rcc,
    '--name', 'orchard_manifest', '--prefix', '/qt/qml/Orchard',
    '--output', '@OUTPUT@', '@INPUT@'])
qml_module = qt6.qml_module('Orchard', version: '1.0',
  qml_sources: qml_wrappers,
  qml_singletons: qml_singletons,
  generate_qmldir: false,
  rcc_extra_arguments: rcc_args,
  moc_headers: qml_type_headers,
  resources_prefix: 'qt/qml',
  include_directories: app_inc,
  dependencies: [qt_dep])
exe_moc = qt6.compile_moc(sources: ['app/src/appearance/liquid_glass.cpp'],
  include_directories: app_inc, dependencies: [qt_dep, qt_gui_private])

adaptive_platform = get_option('adaptive_platform')
if adaptive_platform == ''
  adaptive_arch = host_machine.cpu_family()
  if adaptive_arch == 'amd64'
    adaptive_arch = 'x86_64'
  elif adaptive_arch == 'arm64'
    adaptive_arch = 'aarch64'
  endif
  if host_machine.system() == 'windows'
    adaptive_platform = 'windows-' + adaptive_arch + '-msvc'
  elif host_machine.system() == 'darwin'
    adaptive_platform = 'macos-' + adaptive_arch
  else
    adaptive_platform = 'linux-' + adaptive_arch
  endif
endif
adaptive_stage = custom_target('adaptive mix stage',
  input: [rust_archive] + files(rust_sources + ['scripts/stage-adaptive-mix.py']),
  output: 'adaptive-mix.stamp',
  command: [python, files('scripts/meson-stage-adaptive.py'),
    '--target-dir', cargo_target_dir, '--target', rust_target,
    '--profile', rust_profile, '--platform', adaptive_platform,
    '--source-root', source_root,
    '--output', meson.current_build_dir() / 'adaptive-mix.stamp'],
  build_by_default: true)

orchard = executable('orchard', files(app_executable_sources) + exe_moc +
  qml_module + [qmldir_resources] + qml_files_qrc + assets_qrc + licenses_qrc + docs_qrc + release_notes_qrc +
  [provider_resources, shaders_cpp],
  include_directories: app_inc,
  dependencies: [app_dep, qt_dep, qt_gui_private, qt_entry_dep],
  link_depends: adaptive_stage,
  win_subsystem: 'windows', install: true)

auth_dependencies = [qt_dep, qt_web_dep, rust_dep, qjs_dep, keychain_dep] + platform_deps
auth_args = []
if host_machine.system() == 'windows' or host_machine.system() == 'linux'
  auth_dependencies += [qt_webengine_dep]
  auth_args += ['-DORCHARD_WEBENGINE_COOKIE_ADAPTER', '-DORCHARD_NATIVE_COOKIE_AUTH']
endif
helper_moc = qt6.compile_moc(headers: helper_moc_headers,
  sources: ['app/src/providers/runtime/provider_runtime.cpp'],
  include_directories: app_inc, dependencies: auth_dependencies,
  extra_args: auth_args)
auth_helper = executable('orchard-auth-helper', files(helper_sources) + helper_moc +
  auth_qrc + [auth_provider_resources],
  include_directories: app_inc,
  dependencies: auth_dependencies + [qt_entry_dep], cpp_args: auth_args,
  win_subsystem: 'windows', install: true)

qml_startup_test = executable('orchard_qml_startup_test',
  files('tests/qml_startup_test.cpp'),
  objects: [orchard.extract_objects(qml_module, qmldir_resources, qml_files_qrc, exe_moc,
    files('app/src/appearance/artwork_palette.cpp',
      'app/src/appearance/kawarp_background.cpp',
      'app/src/appearance/liquid_glass.cpp')), app_lib.extract_objects(
    files('app/src/appearance/artwork_sampler.cpp',
      'app/src/appearance/kawarp_artwork.cpp'))],
  include_directories: app_inc,
  dependencies: [qt_dep, qt_gui_private], build_by_default: false)

if get_option('tests')
  test('orchard_qml_startup', qml_startup_test, timeout: 60,
    env: ['QT_QPA_PLATFORM=offscreen'])
  qt_test_dep = dependency('qt6', modules: ['Test'])
  instance_test_moc = qt6.compile_moc(sources: ['tests/instance_coordinator_test.cpp'],
    include_directories: app_inc, dependencies: [qt_test_dep, qt_dep])
  instance_test = executable('orchard_instance_tests',
    files('tests/instance_coordinator_test.cpp', 'app/src/instance_coordinator.cpp') + instance_test_moc,
    include_directories: app_inc, dependencies: [qt_test_dep, qt_dep])
  test('orchard_instance', instance_test, timeout: 15)
  app_test_specs = [
    ['audio_proxy', 'tests/audio_stream_proxy_test.cpp'],
    ['account', 'tests/orchard_account_test.cpp'],
    ['support', 'tests/support_center_test.cpp'],
    ['discord_artwork', 'tests/discord_artwork_test.cpp'],
    ['lastfm', 'tests/lastfm_test.cpp'],
    ['playback_persistence', 'tests/playback_persistence_test.cpp'],
    ['songlink', 'tests/songlink_test.cpp'],
    ['animated_artwork', 'tests/animated_artwork_test.cpp'],
    ['artwork_sampler', 'tests/artwork_sampler_test.cpp'],
    ['streaming_services', 'tests/streaming_services_test.cpp'],
    ['slop_detector', 'tests/slop_detector_test.cpp'],
    ['slop_fingerprint', 'tests/slop_fingerprint_test.cpp'],
    ['local_library', 'tests/local_library_test.cpp'],
    ['offline', 'tests/offline_test.cpp'],
    ['connect_tracks', 'tests/connect/connect_tracks_test.cpp'],
    ['connect_mix', 'tests/connect/connect_mix_test.cpp'],
    ['adaptive_mix_output', 'tests/adaptive_mix_output_test.cpp'],
    ['music_video_match', 'tests/music_video_match_test.cpp'],
    ['letterbox', 'tests/letterbox_test.cpp'],
    ['lyrics_translation', 'tests/lyrics_translation_test.cpp'],
  ]
  foreach spec : app_test_specs
    test_name = 'orchard_' + spec[0]
    test_moc = qt6.compile_moc(sources: [spec[1]],
      include_directories: app_inc, dependencies: [qt_test_dep, app_dep])
    test_exe = executable(test_name + '_tests', files(spec[1]) + test_moc,
      include_directories: app_inc, dependencies: [app_dep, qt_test_dep])
    test_env = spec[0] == 'slop_fingerprint' ? ['ORCHARD_MODELS_DIR=' + source_root / 'models'] : []
    test(test_name, test_exe, timeout: 120, env: test_env)
  endforeach

  auth_test_moc = qt6.compile_moc(
    sources: ['tests/auth_manager_test.cpp'],
    headers: ['app/src/auth/auth_manager.h', 'app/src/auth/auth_session_channel.h',
      'app/src/auth/browser_cookie_adapter.h',
      'app/src/providers/youtube/youtube_provider.h'],
    include_directories: app_inc, dependencies: [qt_test_dep, keychain_dep],
    extra_args: ['-DORCHARD_NATIVE_COOKIE_AUTH'])
  auth_test = executable('orchard_auth_tests', files(
      'tests/auth_manager_test.cpp',
      'app/src/auth/auth_manager.cpp',
      'app/src/auth/auth_account_switch.cpp',
      'app/src/auth/auth_session_channel.cpp') + auth_test_moc,
    include_directories: app_inc,
    dependencies: [keychain_dep, qt_test_dep,
      dependency('qt6', modules: ['Network'])],
    cpp_args: ['-DORCHARD_NATIVE_COOKIE_AUTH'])
  test('orchard_auth', auth_test, timeout: 120)

  media_bytecode = custom_target('provider media test bytecode',
    input: files('tests/data/provider_media_test.js'),
    output: 'provider-media-test.qjc',
    command: [qjsc, '-b', '-C', '-n', 'provider-media-test.js',
      '-o', '@OUTPUT@', '@INPUT@'])
  test_provider_qrc = custom_target('provider test resources',
    input: [youtube_bytecode, po_bytecode, qobuz_bytecode, media_bytecode],
    output: 'qrc_provider_test_resources.cpp',
    command: [python, files('scripts/meson-rcc.py'), '--rcc', rcc,
      '--name', 'provider_test_resources', '--prefix', '/providers',
      '--output', '@OUTPUT@', '@INPUT@'])
  provider_test_moc = qt6.compile_moc(sources: ['tests/provider_runtime_test.cpp'],
    include_directories: app_inc, dependencies: [qt_test_dep, app_dep])
  provider_test = executable('orchard_provider_runtime_tests',
    files('tests/provider_runtime_test.cpp') + provider_test_moc + [test_provider_qrc],
    include_directories: app_inc, dependencies: [app_dep, qt_test_dep])
  test('orchard_provider_runtime', provider_test, timeout: 120)

  docs_test_moc = qt6.compile_moc(sources: ['tests/docs_library_test.cpp'],
    include_directories: app_inc, dependencies: [qt_test_dep, app_dep])
  docs_test = executable('orchard_docs_tests', files('tests/docs_library_test.cpp') +
    docs_test_moc + docs_qrc,
    include_directories: app_inc, dependencies: [app_dep, qt_test_dep],
    cpp_args: ['-DORCHARD_DOCS_DIR="' + source_root / 'docs/app' + '"'])
  test('orchard_docs', docs_test, timeout: 120)

  docs_search_moc = qt6.compile_moc(sources: ['tests/docs_search_test.cpp'],
    include_directories: app_inc, dependencies: [qt_test_dep, app_dep])
  docs_search = executable('orchard_docs_search_tests', files('tests/docs_search_test.cpp') +
    docs_search_moc,
    include_directories: app_inc, dependencies: [app_dep, qt_test_dep],
    cpp_args: [
      '-DORCHARD_DOCS_DIR="' + source_root / 'docs/app' + '"',
      '-DORCHARD_DOCS_MODEL_DIR="' + source_root / 'models/docs-search' + '"',
      '-DORCHARD_MODELS_SOURCE_DIR="' + source_root / 'models' + '"',
      '-DORCHARD_TEST_DATA_DIR="' + source_root / 'tests/data' + '"',
    ])
  test('orchard_docs_search', docs_search, timeout: 120,
    env: ['ORCHARD_MODELS_DIR=' + source_root / 'models'])

  connect_test_specs = [
    ['connect_core', 'tests/connect/connect_core_test.cpp'],
    ['connect_session', 'tests/connect/connect_session_test.cpp'],
  ]
  foreach spec : connect_test_specs
    extra_sources = []
    if spec[0] == 'connect_session'
      extra_sources = files('tests/connect/connect_harness.cpp',
        'tests/connect/connect_harness.h')
    endif
    test_moc = qt6.compile_moc(sources: [spec[1]],
      dependencies: [qt_test_dep, connect_dep])
    test_exe = executable('orchard_' + spec[0] + '_tests',
      files(spec[1]) + test_moc + extra_sources,
      dependencies: [connect_dep, qt_test_dep])
    test('orchard_' + spec[0], test_exe, timeout: 120)
  endforeach

  adaptive_span_moc = qt6.compile_moc(sources: ['tests/adaptive_mix_span_test.cpp'],
    include_directories: app_inc, dependencies: [qt_test_dep])
  adaptive_span = executable('orchard_adaptive_mix_span_tests',
    files('tests/adaptive_mix_span_test.cpp') + adaptive_span_moc,
    include_directories: app_inc,
    dependencies: [dependency('qt6', modules: ['Core']), qt_test_dep])
  test('orchard_adaptive_mix_span', adaptive_span, timeout: 120)

  node = find_program('node', required: false)
  if node.found()
    test('orchard_fullscreen_layout', node,
      args: ['--test', files('tests/fullscreen_layout_test.js')], timeout: 120)
    test('orchard_domain_js', node, args: ['--test'],
      workdir: source_root / 'core/js', timeout: 120)
    test('orchard_qobuz_js', node, args: ['--test'],
      workdir: source_root / 'providers/qobuz', timeout: 120)
    test('orchard_youtube_js', node, args: ['--test'],
      workdir: source_root / 'providers/youtube', timeout: 120)
  endif
endif
