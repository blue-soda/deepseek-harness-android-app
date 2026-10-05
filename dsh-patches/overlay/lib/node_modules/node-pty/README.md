# node-pty-android-arm64

The npm package is a scriptless Android ARM64 prebuilt: installation does not
run `install` or `postinstall` hooks and does not compile on the device. The
tracked `prebuilds/android-arm64/pty.node` is loaded directly by the standard
node-pty loader. Its SHA-256 is
`660a3025230f6035b7b8c000e8cca6ca3992bedaa05f7b165e7c3a5f1ae8ec8a`.

Android/Termux-only fork of node-pty targeting ARM64 (bionic). It provides the same API as the upstream `node-pty` package but is focused on working reliably under Termux.

## Scope

- Platform: `android`
- CPU: `arm64`
- Intended environment: Termux on Android

If you need Linux/macOS/Windows support, use the upstream project: https://github.com/microsoft/node-pty

## Install

```bash
npm install node-pty-android-arm64
```

If you want to keep `require('node-pty')` in your code, you can use an npm alias:

```bash
npm install node-pty@npm:node-pty-android-arm64
```

## Build on Termux

Prerequisites:

```bash
pkg install -y nodejs python make clang pkg-config git
```

Build and install:

```bash
npm install
npm run build
```

Notes:
- If `prebuilds/android-arm64` exists, it will be used.
- When building from source on Termux, `android_ndk_path` is derived from `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT` or falls back to `$PREFIX`.

## Usage

```js
import * as os from 'node:os';
import * as pty from 'node-pty';

const shell = os.platform() === 'win32' ? 'powershell.exe' : 'bash';

const ptyProcess = pty.spawn(shell, [], {
  name: 'xterm-color',
  cols: 80,
  rows: 30,
  cwd: process.env.HOME,
  env: process.env
});

ptyProcess.onData((data) => {
  process.stdout.write(data);
});

ptyProcess.write('ls\r');
```

## Credits

Based on the original `node-pty` project by Microsoft and contributors.

## License

Original `node-pty` work by Microsoft, contributors, and prior maintainers (as credited in the project and license).<br>
MIT License (see [LICENSE](LICENSE)).<br>
Termux-port maintenance by Davide A. Guglielmi<br>
Made in Italy 🇮🇹

---

## Fork maintainer

This fork is maintained by [DioNanos](https://github.com/DioNanos) for Termux/Android use.

- Fork issues (build, packaging, mobile-specific): **dev@mmmbuto.com**
- Fork security disclosures: **security@mmmbuto.com**

Upstream-relevant issues should be reported on the upstream project.
