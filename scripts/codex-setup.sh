#!/usr/bin/env bash
# Run from any directory: bash /workspace/Rikka-Root/scripts/codex-setup.sh
# Uses this checkout, never the repository's default branch. No signing/Firebase secrets.
# Tools live outside the repository; reruns reuse installations and read-only refs.
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
setup_dir="${CODEX_SETUP_DIR:-$HOME/.local/share/rikka-root}"
gradle_dir="${GRADLE_USER_HOME:-$HOME/.gradle}"
refs_dir=/workspace/refs
log() { printf '\n[codex-setup] %s\n' "$*"; }
fail() { printf '[codex-setup] ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || fail 'Requires Linux x86_64.'
missing_packages=()
for tool in git curl python3 unzip tar gzip sha256sum sha512sum sha1sum; do
    if ! command -v "$tool" >/dev/null; then
        case "$tool" in
            sha*sum) missing_packages+=(coreutils) ;;
            *) missing_packages+=("$tool") ;;
        esac
    fi
done
if ((${#missing_packages[@]})); then
    log "Installing base tools: ${missing_packages[*]}"
    if [[ $EUID == 0 ]]; then
        apt_command=(apt-get)
    elif command -v sudo >/dev/null && sudo -n true 2>/dev/null; then
        apt_command=(sudo -n apt-get)
    else
        fail 'Missing base tools require apt-get as root or passwordless sudo.'
    fi
    "${apt_command[@]}" update
    "${apt_command[@]}" install -y --no-install-recommends ca-certificates "${missing_packages[@]}"
fi

mkdir -p "$setup_dir" "$gradle_dir" "$refs_dir"
setup_tmp="$(mktemp -d "$setup_dir/.setup.XXXXXX")"
trap 'rm -rf -- "$setup_tmp"' EXIT
download() { curl --fail --location --silent --show-error --retry 3 --connect-timeout 30 "$1" --output "$2"; }
verify() { printf '%s  %s\n' "$2" "$3" | "$1" --check --status || fail "Checksum mismatch: $3"; }

# Read requirements from the checked-out branch rather than copying upstream defaults.
python3 - "$repo_dir" > "$setup_tmp/requirements.sh" <<'PY'
import pathlib, re, shlex, sys
repo = pathlib.Path(sys.argv[1])
workflow = (repo / '.github/workflows/daily-build.yml').read_text()
def required(pattern):
    match = re.search(pattern, workflow)
    if not match:
        raise SystemExit(f'Cannot find workflow requirement: {pattern}')
    return match.group(1)
java = required(r"java-version:\s*['\"]?(\d+)")
node = required(r"node-version:\s*['\"]?(\d+)")
pnpm = required(r"uses: pnpm/action-setup@[^\n]+\s+with:\s+version:\s*['\"]?(\d+)")
daemon = (repo / 'gradle/gradle-daemon-jvm.properties').read_text()
if java != '21' or not re.search(r'^toolchainVersion=21$', daemon, re.M):
    raise SystemExit('This setup pins JDK 21; update it if the project changes Java requirements.')
if not re.search(r'^toolchainVendor=JETBRAINS$', daemon, re.M):
    raise SystemExit('Review the daemon JDK installation: its required vendor changed.')
packages = set(re.findall(r"'(platforms;[^']+|build-tools;[^']+|ndk;[^']+|cmake;[^']+)'", workflow))
packages.add('platform-tools')
for file in ('app/build.gradle.kts', 'app/baselineprofile/build.gradle.kts',
             'build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt'):
    content = (repo / file).read_text()
    for major, minor in re.findall(r'release\((\d+)\)\s*\{\s*minorApiLevel\s*=\s*(\d+)', content):
        packages.add(f'platforms;android-{major}.{minor}')
    for major in re.findall(r'compileSdk\s*=\s*(\d+)', content):
        packages.add(f'platforms;android-{major}.0')
build_tools = sorted(p.split(';', 1)[1] for p in packages if p.startswith('build-tools;'))
if len(build_tools) != 1:
    raise SystemExit('Expected one build-tools version in the workflow.')
# AGP 9.4 defaults to 36.0.0 when buildToolsVersion is not explicitly set.
# Keep 37.0.0 from CI for apksigner, and preinstall the version compilation uses.
packages.add('build-tools;36.0.0')
for key, value in (('java_major', java), ('node_major', node), ('pnpm_major', pnpm),
                   ('build_tools_version', build_tools[0])):
    print(f'{key}={shlex.quote(value)}')
print('sdk_packages=(' + ' '.join(shlex.quote(p) for p in sorted(packages)) + ')')
PY
source "$setup_tmp/requirements.sh"

is_jdk21() {
    [[ -x "$1/bin/javac" && -f "$1/release" ]] &&
        grep -q '^JAVA_VERSION="21\.' "$1/release"
}
temurin_dir="$setup_dir/temurin-$java_major"
if ! is_jdk21 "$temurin_dir"; then
    log "Installing Temurin JDK $java_major"
    download "https://api.adoptium.net/v3/assets/latest/$java_major/hotspot?architecture=x64&os=linux&image_type=jdk" "$setup_tmp/temurin.json"
    python3 - "$setup_tmp/temurin.json" > "$setup_tmp/temurin-download" <<'PY'
import json, sys
package = json.load(open(sys.argv[1]))[0]['binary']['package']
print(package['link'])
print(package['checksum'])
PY
    mapfile -t temurin_download < "$setup_tmp/temurin-download"
    download "${temurin_download[0]}" "$setup_tmp/temurin.tar.gz"
    verify sha256sum "${temurin_download[1]}" "$setup_tmp/temurin.tar.gz"
    mkdir "$setup_tmp/temurin"
    tar -xzf "$setup_tmp/temurin.tar.gz" --strip-components=1 -C "$setup_tmp/temurin"
    is_jdk21 "$setup_tmp/temurin" || fail 'Invalid Temurin JDK archive.'
    [[ ! -e "$temurin_dir" ]] || fail "Incomplete installation at $temurin_dir; move it aside and rerun."
    mv "$setup_tmp/temurin" "$temurin_dir"
fi

# Daemon JVM criteria take precedence over JAVA_HOME and explicitly require JetBrains.
jbr_dir="$setup_dir/jbr-21"
if ! is_jdk21 "$jbr_dir"; then
    log 'Installing JetBrains JDK 21 for Gradle daemon criteria'
    download 'https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-21.0.11-linux-x64-b1163.116.tar.gz' "$setup_tmp/jbr.tar.gz"
    verify sha512sum '13d0aab4bcfa9e4f77a696b97d3998f7e82e22f7f1362d8ce97f0d50d69dddd9331a66ada3fe92c04e544795fc247bcbaf586ad0415df4eda5e0e0505753888f' "$setup_tmp/jbr.tar.gz"
    mkdir "$setup_tmp/jbr"
    tar -xzf "$setup_tmp/jbr.tar.gz" --strip-components=1 -C "$setup_tmp/jbr"
    is_jdk21 "$setup_tmp/jbr" || fail 'Invalid JetBrains JDK archive.'
    [[ ! -e "$jbr_dir" ]] || fail "Incomplete installation at $jbr_dir; move it aside and rerun."
    mv "$setup_tmp/jbr" "$jbr_dir"
fi

node_dir="$setup_dir/node-$node_major"
if [[ ! -x "$node_dir/bin/node" ]] || [[ "$("$node_dir/bin/node" --version)" != v"$node_major".* ]]; then
    log "Installing Node $node_major"
    download "https://nodejs.org/dist/latest-v$node_major.x/SHASUMS256.txt" "$setup_tmp/node-shasums"
    read -r node_checksum node_archive < <(awk '/ node-v.*-linux-x64\.tar\.gz$/ {print; exit}' "$setup_tmp/node-shasums")
    [[ -n "$node_archive" ]] || fail 'Node release manifest has no Linux x64 archive.'
    download "https://nodejs.org/dist/latest-v$node_major.x/$node_archive" "$setup_tmp/node.tar.gz"
    verify sha256sum "$node_checksum" "$setup_tmp/node.tar.gz"
    mkdir "$setup_tmp/node"
    tar -xzf "$setup_tmp/node.tar.gz" --strip-components=1 -C "$setup_tmp/node"
    [[ ! -e "$node_dir" ]] || fail "Incomplete installation at $node_dir; move it aside and rerun."
    mv "$setup_tmp/node" "$node_dir"
fi
export JAVA_HOME="$temurin_dir"
export PATH="$node_dir/bin:$JAVA_HOME/bin:$PATH"
pnpm_dir="$setup_dir/pnpm"
# The image's pnpm shim hardcodes Node 24, so install a private CLI using Node 22.
if [[ ! -x "$pnpm_dir/node_modules/.bin/pnpm" ]] ||
    [[ "$("$pnpm_dir/node_modules/.bin/pnpm" --version)" != "$pnpm_major".* ]]; then
    log "Installing pnpm $pnpm_major under Node $node_major"
    npm install --prefix "$pnpm_dir" --no-package-lock --no-audit --no-fund "pnpm@$pnpm_major"
fi

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$setup_dir/android-sdk}}"
sdkmanager="$sdk_dir/cmdline-tools/latest/bin/sdkmanager"
mkdir -p "$sdk_dir"
if [[ ! -x "$sdkmanager" ]]; then
    log 'Installing Android SDK command-line tools'
    download 'https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip' "$setup_tmp/android-tools.zip"
    verify sha1sum 'e025545c62a8e64c7559119566a569fb1dec5f60' "$setup_tmp/android-tools.zip"
    unzip -q "$setup_tmp/android-tools.zip" -d "$setup_tmp/android-tools"
    mkdir -p "$sdk_dir/cmdline-tools"
    [[ ! -e "$sdk_dir/cmdline-tools/latest" ]] || fail 'Incomplete Android command-line tools; move latest aside and rerun.'
    mv "$setup_tmp/android-tools/cmdline-tools" "$sdk_dir/cmdline-tools/latest"
fi

# Keep the runtime's proxy and TLS verification, including its injected CA trust.
trust_store="$setup_dir/java-cacerts"
if [[ ! -f "$trust_store" ]]; then
    if [[ -f /etc/ssl/certs/java/cacerts ]]; then
        cp /etc/ssl/certs/java/cacerts "$trust_store"
    else
        cp "$JAVA_HOME/lib/security/cacerts" "$trust_store"
    fi
fi
if [[ -n "${CODEX_PROXY_CERT:-}" && -f "$CODEX_PROXY_CERT" ]]; then
    if ! "$JAVA_HOME/bin/keytool" -list -alias rikka-codex-proxy -keystore "$trust_store" -storepass changeit >/dev/null 2>&1; then
        "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias rikka-codex-proxy -file "$CODEX_PROXY_CERT" \
            -keystore "$trust_store" -storepass changeit
    fi
fi
python3 - "$setup_dir/env.sh" "$temurin_dir" "$node_dir" "$pnpm_dir" "$sdk_dir" "$build_tools_version" "$trust_store" <<'PY'
import pathlib, shlex, sys
file, java, node, pnpm, sdk, tools, trust = sys.argv[1:]
q = shlex.quote
text = f'export JAVA_HOME={q(java)}\nexport ANDROID_HOME={q(sdk)}\nexport ANDROID_SDK_ROOT="$ANDROID_HOME"\n'
for directory in reversed([java+'/bin', node+'/bin', pnpm+'/node_modules/.bin',
                           sdk+'/cmdline-tools/latest/bin', sdk+'/platform-tools', sdk+'/build-tools/'+tools]):
    text += f'case ":$PATH:" in *:{q(directory)}:*) ;; *) export PATH={q(directory)}:"$PATH" ;; esac\n'
flag = '-Djavax.net.ssl.trustStore=' + trust
text += f'case "${{JAVA_TOOL_OPTIONS:-}}" in *{q(flag)}*) ;; *) export JAVA_TOOL_OPTIONS="${{JAVA_TOOL_OPTIONS:-}} "{q(flag)} ;; esac\n'
pathlib.Path(file).write_text(text)
PY
source "$setup_dir/env.sh"
for profile in "$HOME/.profile" "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.bash_login"; do
    [[ -f "$profile" || "$profile" == "$HOME/.profile" ]] || continue
    printf -v profile_line '. %q # Rikka-Root Codex toolchain' "$setup_dir/env.sh"
    grep -Fqx "$profile_line" "$profile" 2>/dev/null || printf '\n%s\n' "$profile_line" >> "$profile"
done

python3 - "$gradle_dir/gradle.properties" "$repo_dir/local.properties" "$temurin_dir" "$jbr_dir" "$sdk_dir" "$trust_store" "$setup_tmp/proxy.sh" <<'PY'
import os, pathlib, re, shlex, sys
from urllib.parse import urlparse
gradle_file, local_file, temurin, jbr, sdk, trust, proxy_file = sys.argv[1:]
def escape(value):
    return value.replace('\\', '\\\\').replace(':', '\\:').replace('=', '\\=').replace(' ', '\\ ')
properties = {'org.gradle.java.installations.paths': temurin+','+jbr,
              'org.gradle.workers.max': '4', 'systemProp.javax.net.ssl.trustStore': trust}
proxy_args = []
for protocol in ('http', 'https'):
    proxy = urlparse(os.environ.get(protocol.upper()+'_PROXY') or os.environ.get(protocol+'_proxy') or '')
    if proxy.hostname:
        port = proxy.port or (443 if proxy.scheme == 'https' else 80)
        properties[f'systemProp.{protocol}.proxyHost'] = proxy.hostname
        properties[f'systemProp.{protocol}.proxyPort'] = str(port)
        if protocol == 'https':
            proxy_args = ['--proxy=http', '--proxy_host='+proxy.hostname, '--proxy_port='+str(port)]
properties['systemProp.http.nonProxyHosts'] = 'localhost|127.*|[::1]'
start, end = '# BEGIN Rikka-Root Codex setup', '# END Rikka-Root Codex setup'
file = pathlib.Path(gradle_file)
old = file.read_text() if file.exists() else ''
old = re.sub(re.escape(start)+r'.*?'+re.escape(end)+r'\n?', '', old, flags=re.S).rstrip()
block = '\n'.join(key+'='+escape(value) for key,value in properties.items())
file.write_text((old+'\n\n' if old else '')+start+'\n'+block+'\n'+end+'\n')
# sdk.dir also makes Android discovery work in shells that have not sourced env.sh.
file = pathlib.Path(local_file)
old = file.read_text() if file.exists() else ''
old = re.sub(r'^sdk\.dir\s*[=:].*\n?', '', old, flags=re.M).rstrip()
file.write_text((old+'\n' if old else '')+'sdk.dir='+escape(sdk)+'\n')
pathlib.Path(proxy_file).write_text('sdk_proxy_args=('+ ' '.join(map(shlex.quote, proxy_args))+')\n')
PY
source "$setup_tmp/proxy.sh"
missing_sdk=()
for package in "${sdk_packages[@]}"; do
    [[ -f "$sdk_dir/${package//;/\/}/package.xml" ]] || missing_sdk+=("$package")
done
if ((${#missing_sdk[@]})); then
    log 'Accepting Android SDK licenses noninteractively'
    # yes receives SIGPIPE when sdkmanager exits; only sdkmanager's status matters.
    set +o pipefail
    yes | "$sdkmanager" --sdk_root="$sdk_dir" "${sdk_proxy_args[@]}" --licenses
    set -o pipefail
    log "Installing Android packages: ${missing_sdk[*]}"
    "$sdkmanager" --sdk_root="$sdk_dir" "${sdk_proxy_args[@]}" "${missing_sdk[@]}"
fi

clone_reference() {
    local name="$1" url="$2" destination="$refs_dir/$1"
    if [[ ! -e "$destination" ]]; then
        log "Cloning read-only reference: $name (depth 1)"
        local staging
        staging="$(mktemp -d "$setup_tmp/ref.XXXXXX")"
        GIT_TERMINAL_PROMPT=0 git -c credential.helper= clone --depth 1 --single-branch --no-tags "$url" "$staging"
        git -C "$staging" remote set-url --push origin 'disabled://read-only'
        mv "$staging" "$destination"
    fi
    [[ "$(git -C "$destination" remote get-url origin)" == "$url" ]] || fail "Unexpected reference repository at $destination"
    [[ "$(git -C "$destination" rev-parse --is-shallow-repository)" == true ]] || fail "Reference must be shallow: $destination"
    [[ "$(git -C "$destination" remote get-url --push origin)" == disabled://read-only ]] || fail "Reference push is not disabled: $destination"
    chmod -R a-w "$destination"
}
clone_reference rikkahub-agent https://github.com/ExTV/rikkahub-agent
clone_reference Moru https://github.com/mishaqp/Moru

log 'Initializing the project submodule at its recorded commit'
git -C "$repo_dir" submodule update --init --recursive --depth 1
log 'Installing web-ui dependencies without changing its lockfile'
(cd "$repo_dir/web-ui" && CI=1 pnpm install --frozen-lockfile)

log 'Warming Gradle (help only; no APK build or signing)'
(cd "$repo_dir" && ./gradlew help --no-daemon)
log "Ready. For an existing shell: source $setup_dir/env.sh"
java -version
javac -version
node --version
pnpm --version
apksigner version
