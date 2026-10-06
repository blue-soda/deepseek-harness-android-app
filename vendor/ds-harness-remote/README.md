# DeepSeek Harness Remote

Continue DeepSeek Harness sessions and experimental Codex workspaces from another device over an end-to-end encrypted connection.

[GitHub](https://github.com/blue-soda/ds-harness-remote) · [Full guide](https://github.com/blue-soda/ds-harness-remote#readme) · [中文说明](https://github.com/blue-soda/ds-harness-remote/blob/main/README.zh.md) · [Releases](https://github.com/blue-soda/ds-harness-remote/releases) · [Android app](https://github.com/blue-soda/deepseek-harness-android-app/tree/master)

`ds-harness-remote` is the Remote Host and workspace plugin for DeepSeek Harness. Harness keeps running on your work computer with its existing workspaces, tools, and permission controls; Remote gives authorized devices another window into that environment.

> Install this package with `dsh plugin`, not `npm install`. The DSH command updates the selected profile and adds the required bundle configuration.

> **This is a fork** of [liguobao/ds-harness-remote](https://github.com/liguobao/ds-harness-remote) (MIT),
> published as [`@blue-soda/dsh-remote`](https://www.npmjs.com/package/@blue-soda/dsh-remote).
> The original project and its contributors are credited in [LICENSE](LICENSE) and `contributors`.

## Install

### DSH Desktop

[DSH Desktop](https://github.com/liguobao/dsh-desktop) includes Remote and enables it by default. No separate plugin installation is required.

### Existing DSH installation

Add the current package version to the `web` profile, then restart Harness:

```sh
dsh plugin --profile web add -w @blue-soda/dsh-remote@0.4.29
```

### dsh-TUI Host

Remote can also run as a Host in a terminal-only [dsh-TUI](https://github.com/ccch1mneyyy/dsh-TUI) profile:

```sh
dsh plugin --profile dsh-tui add -w @blue-soda/dsh-remote@0.4.29
```

After starting dsh-TUI, manage Remote with `/remote`, `/remote login`, `/remote status`, and `/remote logout`.

## Highlights

- Continue active Harness sessions and review progress from another computer, the web, or Android.
- Send text and image prompts, answer questions, and handle permission requests.
- Open workspaces on another authorized computer without replacing the native Harness interface.
- Use the official workspace file tree and bounded read-only previews on supported Harness versions.
- Use an optional Host-local terminal and authorized loopback development-service previews.
- Open Host Codex projects in the existing Remote UI through the optional experimental Codex domain.
- Reach the Host without opening a public listening port or configuring router port forwarding.

## Compatibility

Plugin `0.4.29` targets DeepSeek Harness `dsh-v0.2.0-rc.2` and retains `dsh-v0.1.7-rc.1` compatibility; it also supports `dsh-v0.1.6-alpha.2` and earlier settings hosts. It supports:

- `dsh-v0.1.1-rc.2` through the official legacy `ApiProxy`;
- `dsh-v0.1.2-alpha.1` through `dsh-v0.1.2-rc.1` through the official Typert Remote Gateway;
- `dsh-v0.1.5-rc.1`, `dsh-v0.1.6-alpha.1`, and `dsh-v0.2.0-rc.2` Session V3 through the official Typert Remote Gateway.

The same package feature-detects the older settings registry and the `0.1.7-rc.1` and `0.2.0-rc.2` Volatile settings entry. Remote Web/Desktop and Android normalize released sessions that still report the retired `code` agent preset to `ptc`. The Host reads the running Harness version from the CLI entrypoint, or from the 0.2.0 Desktop shell's `@deepseek-ai/dsh-desktop-host` entrypoint when the Harness CLI package is only a sibling of that entrypoint inside `app.asar`.

## Self-hosted Server

This repository includes a self-hosted, Relay-only Server in [`apps/server`](https://github.com/blue-soda/ds-harness-remote/tree/main/apps/server) that supports multiple accounts, with devices and tokens namespaced per account. `DSH_SERVER_ACCOUNT` and `DSH_SERVER_PASSWORD` configure a seed account; account creation is off by default and can be enabled with `DSH_SERVER_REGISTRATION_CODE`, or replaced by DeepSeek platform sign-in (`DSH_SERVER_DEEPSEEK_LOGIN=on`) or a QR provider (`DSH_SERVER_OAUTH_PROVIDER`). It provides account login, device credentials, encrypted Control/Noise forwarding, Relay, and a device-status page. It does not provide the complete site - Remote Web session UI or Admin - or WebRTC/TURN deployment.

## Security boundary

- Session traffic is encrypted on the Client and decrypted only by the selected Host using `Noise_IK_25519_ChaChaPoly_SHA256`.
- Account membership and the Host's locally pinned device identity must both authorize a connection.
- The Host creates outbound connections only; it does not listen on a public port.
- Remote does not expose general tool RPC, remote desktop, or file-mutation APIs.
- Interactive terminals are enabled by default and can be disabled in the Host-local Remote settings; they run as the Host user independently of Agent approvals.

## Documentation

- [Complete guide](https://github.com/blue-soda/ds-harness-remote#readme)
- [dsh-TUI Remote guide](https://github.com/blue-soda/ds-harness-remote/blob/main/docs/dsh-tui.md)
- [Codex Remote technical notes](https://github.com/blue-soda/ds-harness-remote/blob/main/docs/codex-remote.md)
- [End-to-end encryption](https://github.com/blue-soda/ds-harness-remote/blob/main/docs/end-to-end-encryption.md)
- [Network and transport](https://github.com/blue-soda/ds-harness-remote/blob/main/docs/network.md)
- [Protocol reference](https://github.com/blue-soda/ds-harness-remote/blob/main/docs/protocol.md)
- [Changelog](https://github.com/blue-soda/ds-harness-remote/blob/main/CHANGELOG.md)

This is an independent community project and is not an official DeepSeek product. Licensed under the [MIT License](./LICENSE).
