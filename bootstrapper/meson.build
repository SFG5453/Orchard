# Orchard bootstrapper: installer, updater, repair tool, rollback manager and
# launcher in one small native binary. Standalone so it builds (and cross
# builds) without Qt, Cargo or npm. Vendored mbedTLS and nlohmann/json come
# from the parent repository's third_party submodules.
# The canary boot gets new directions before the orchard moves house.
project('orchard-bootstrapper', ['c', 'cpp'], version: '1.0.1', meson_version: '>=1.3.0',
  default_options: ['cpp_std=c++20', 'c_std=c99', 'optimization=s', 'debug=false', 'strip=true',
    'b_ndebug=true', 'warning_level=2', 'b_lto=true', 'b_vscrt=mt'])

cc = meson.get_compiler('c')
cpp = meson.get_compiler('cpp')
system = host_machine.system()
third_party = meson.current_source_dir() / '..' / 'third_party'
python = find_program('python3')

# Section GC drops the unused half of mbedTLS (DTLS, server code, legacy ciphers).
size_args = cc.get_argument_syntax() == 'gcc' ? ['-ffunction-sections', '-fdata-sections'] : ['/Gy', '/Gw']
size_link_args = []
if cc.get_argument_syntax() == 'gcc'
  size_link_args += cc.get_supported_link_arguments('-Wl,--gc-sections', '-Wl,--as-needed')
  # Ship one file: no libstdc++ / libgcc / winpthread version roulette.
  size_link_args += ['-static-libstdc++', '-static-libgcc']
  if system == 'windows'
    size_link_args += ['-static']
  endif
else
  size_link_args += ['/OPT:REF', '/OPT:ICF']
endif

mbedtls_config = meson.current_source_dir() / 'mbedtls_bootstrap_config.h'
mbedtls_sources = run_command(python, '-c',
  'import glob, sys; print("\\n".join(sorted(glob.glob(sys.argv[1] + "/library/*.c"))))',
  third_party / 'mbedtls', check: true).stdout().strip().split('\n')
mbedtls_inc = include_directories('../third_party/mbedtls/include', '../third_party/mbedtls/library')
mbedtls_args = size_args + ['-DMBEDTLS_USER_CONFIG_FILE="' + mbedtls_config + '"']
if cc.get_argument_syntax() == 'gcc'
  mbedtls_args += ['-w']
endif
mbedtls_deps = [dependency('threads')]
if system == 'windows'
  mbedtls_deps += [cc.find_library('bcrypt'), cc.find_library('ws2_32')]
endif
mbedtls_lib = static_library('mbedtls_bootstrap', mbedtls_sources,
  include_directories: mbedtls_inc, c_args: mbedtls_args, dependencies: mbedtls_deps)
mbedtls_dep = declare_dependency(link_with: mbedtls_lib, include_directories: mbedtls_inc,
  compile_args: ['-DMBEDTLS_USER_CONFIG_FILE="' + mbedtls_config + '"'], dependencies: mbedtls_deps)

sources = files(
  'src/main.cpp',
  'src/app/Bootstrapper.cpp',
  'src/app/Cli.cpp',
  'src/install/Activation.cpp',
  'src/install/InstallState.cpp',
  'src/install/Installer.cpp',
  'src/install/Layout.cpp',
  'src/install/Repair.cpp',
  'src/install/Stager.cpp',
  'src/launch/Launcher.cpp',
  'src/network/Downloader.cpp',
  'src/network/Http.cpp',
  'src/network/HttpWire.cpp',
  'src/security/Hash.cpp',
  'src/security/Signature.cpp',
  'src/security/TrustedKeys.cpp',
  'src/storage/ObjectStore.cpp',
  'src/ui/ConsoleUi.cpp',
  'src/update/Manifest.cpp',
  'src/update/ReleaseClient.cpp',
  'src/update/SelfUpdate.cpp',
  'src/update/UpdateManager.cpp',
  'src/update/UpdateStatus.cpp',
  'src/util/Files.cpp',
  'src/util/Log.cpp',
  'src/util/Version.cpp',
)
platform_deps = []
platform_args = []
win_subsystem = 'console'
if system == 'linux'
  sources += files(
    'src/network/MbedTlsHttp.cpp',
    'src/platform/linux/Platform.cpp',
    'src/platform/linux/Spawn.cpp',
    'src/platform/linux/ZenityUi.cpp',
  )
elif system == 'windows'
  sources += files(
    'src/platform/windows/Integration.cpp',
    'src/platform/windows/Platform.cpp',
    'src/platform/windows/Win32Ui.cpp',
    'src/platform/windows/WinHttpSession.cpp',
  )
  sources += import('windows').compile_resources('src/platform/windows/bootstrapper.rc',
    depend_files: files('src/platform/windows/orchard.ico', 'src/platform/windows/bootstrapper.manifest'))
  foreach lib : ['winhttp', 'shell32', 'ole32', 'uuid', 'advapi32', 'gdi32', 'user32', 'comctl32']
    platform_deps += cpp.find_library(lib)
  endforeach
  win_subsystem = 'windows'
  platform_args += ['-DUNICODE', '-D_UNICODE', '-DWIN32_LEAN_AND_MEAN', '-DNOMINMAX']
  if cpp.get_argument_syntax() == 'msvc'
    # Keep main() as the entry point under the GUI subsystem.
    size_link_args += ['/ENTRY:mainCRTStartup']
  endif
else
  error('the Orchard bootstrapper has no ' + system + ' platform layer yet')
endif

src_inc = include_directories('src')
json_inc = include_directories('../third_party/json/single_include')
common_args = size_args + platform_args + ['-DORCHARD_BOOTSTRAPPER_VERSION="' + meson.project_version() + '"',
  '-DJSON_NO_IO']
deps = [mbedtls_dep] + platform_deps

executable('orchard-bootstrapper', sources,
  include_directories: [src_inc, json_inc], cpp_args: common_args,
  link_args: size_link_args, dependencies: deps, win_subsystem: win_subsystem, install: true)

if get_option('tests')
  # Same code plus an env-provided signing key, so tests never need the release key.
  test_exe = executable('orchard-bootstrapper-test', sources,
    include_directories: [src_inc, json_inc], cpp_args: common_args + ['-DORCHARD_BOOTSTRAP_TEST_HOOKS'],
    link_args: size_link_args, dependencies: deps, win_subsystem: win_subsystem)
  if system == 'linux'
    test('end-to-end', python, args: [files('tests/e2e_test.py'), test_exe.full_path(),
      meson.current_source_dir() / '..' / 'scripts' / 'release' / 'orchard_release.py'],
      depends: test_exe, timeout: 300, is_parallel: false)
  endif
endif
