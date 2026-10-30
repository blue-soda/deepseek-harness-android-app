"use strict";
(() => {
  // src/remote-file-content-provider.ts
  function shouldUseRemoteFileViewer(status) {
    return status.mode === "remote" && status.remoteFeatures?.fileViewer === !0;
  }
  var REMOTE_FILE_SAVE_AS_MAX_BYTES = 100 * 1024 * 1024, REMOTE_FILE_FAST_SAVE_AS_MAX_BYTES = 1024 * 1024 * 1024;
  function shouldAllowRemoteFileSaveAs(status) {
    return shouldUseRemoteFileViewer(status) && (status.transport === "LAN" || status.transport === "P2P" || status.transport === "TURN");
  }
  function remoteFileSaveAsMaxBytes(status) {
    return status.transport === "LAN" || status.transport === "P2P" ? REMOTE_FILE_FAST_SAVE_AS_MAX_BYTES : REMOTE_FILE_SAVE_AS_MAX_BYTES;
  }
  function createRemoteFileContentProvider(call, options = {}) {
    return {
      id: "dsh-remote-files",
      priority: 1e4,
      supports: () => !0,
      saveAsAllowed: () => ({
        allowed: currentSaveAsAllowed(options.saveAsAllowed),
        maxBytes: currentSaveAsMaxBytes(options.saveAsMaxBytes)
      }),
      async stat(locator, signal) {
        let value = await call("fileviewer.stat", { path: locator }, signal);
        if (value.exists)
          return {
            name: value.name,
            size: value.isDirectory ? 0 : value.size,
            mime: value.mime,
            mtimeMs: value.mtimeMs,
            isDirectory: value.isDirectory
          };
      },
      async read(locator, request) {
        if (!Number.isInteger(request.offset) || request.offset < 0) throw new Error("A non-negative integer offset is required.");
        if (!Number.isInteger(request.length) || request.length <= 0) throw new Error("A positive integer length is required.");
        let chunks = [], received = 0;
        for (; received < request.length; ) {
          request.signal.throwIfAborted();
          let length = Math.min(524288, request.length - received), offset = request.offset + received, range = await call("fileviewer.readRange", { path: locator, offset, length }, request.signal);
          if (range.offset !== offset) throw new Error("The Remote Host returned a mismatched file range.");
          let bytes = decodeBase64(range.data);
          if (bytes.byteLength > length) throw new Error("The Remote Host returned more file bytes than requested.");
          if (chunks.push(bytes), received += bytes.byteLength, range.eof || bytes.byteLength === 0) break;
        }
        let merged = new Uint8Array(received), cursor = 0;
        for (let chunk of chunks)
          merged.set(chunk, cursor), cursor += chunk.byteLength;
        return merged;
      },
      async list(locator, signal) {
        return (await call("fileviewer.list", { path: locator }, signal)).entries.map((entry) => ({
          locator: entry.path,
          name: entry.name,
          size: entry.isDirectory ? 0 : entry.size ?? 0,
          mtimeMs: entry.mtimeMs,
          isDirectory: entry.isDirectory
        }));
      }
    };
  }
  function currentSaveAsAllowed(value) {
    return typeof value == "function" ? value() : value === !0;
  }
  function currentSaveAsMaxBytes(value) {
    return typeof value == "function" ? value() : value ?? REMOTE_FILE_SAVE_AS_MAX_BYTES;
  }
  function decodeBase64(value) {
    let binary = atob(value), bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  // src/control-route.ts
  var CONTROL_RPC_PREFIX = "/ds-harness-remote", STATUS_STREAM_ENDPOINT = "status.events", STATUS_STREAM_PATH = `${CONTROL_RPC_PREFIX}/${STATUS_STREAM_ENDPOINT}`;

  // src/status-stream.ts
  function parseStatus(data) {
    if (typeof data == "string")
      try {
        let parsed = JSON.parse(data);
        return parsed === null || typeof parsed != "object" ? void 0 : parsed;
      } catch {
        return;
      }
  }
  function createStatusFeed(options) {
    let listeners = /* @__PURE__ */ new Set(), pollIntervalMs = options.pollIntervalMs ?? 1500, openTimeoutMs = options.openTimeoutMs ?? 4e3, createSource = options.createSource ?? ((url) => new EventSource(url)), status, source, openTimer, pollTimer, publish = (next) => {
      status = next;
      for (let listener of [...listeners]) listener(next);
    }, teardown = () => {
      openTimer !== void 0 && (clearTimeout(openTimer), openTimer = void 0), pollTimer !== void 0 && (clearInterval(pollTimer), pollTimer = void 0), source?.close(), source = void 0;
    }, poll = () => {
      options.readStatus().then((next) => {
        pollTimer !== void 0 && publish(next);
      }).catch(() => {
      });
    }, fallbackToPolling = (reason) => {
      pollTimer !== void 0 || listeners.size === 0 || (openTimer !== void 0 && (clearTimeout(openTimer), openTimer = void 0), source?.close(), source = void 0, options.onFallback?.(reason), poll(), pollTimer = setInterval(poll, pollIntervalMs));
    }, openStream = () => {
      let opened;
      try {
        opened = createSource(options.url);
      } catch {
        fallbackToPolling("unsupported");
        return;
      }
      source = opened;
      let received = !1;
      openTimer = setTimeout(() => {
        received || fallbackToPolling("silent");
      }, openTimeoutMs), opened.onmessage = (event) => {
        received = !0, openTimer !== void 0 && (clearTimeout(openTimer), openTimer = void 0);
        let next = parseStatus(event.data);
        next !== void 0 && publish(next);
      }, opened.onerror = () => {
        opened.readyState === 2 && fallbackToPolling("unavailable");
      };
    };
    return {
      getSnapshot: () => status,
      subscribe: (listener) => (listeners.add(listener), status !== void 0 && listener(status), listeners.size === 1 && openStream(), () => {
        listeners.delete(listener), listeners.size === 0 && teardown();
      }),
      close: () => {
        listeners.clear(), teardown();
      }
    };
  }

  // src/defaults.ts
  var DEFAULT_REMOTE_SERVER_URL = "https://sakakibara.ink:8443";

  // src/panel-authorization.ts
  function authorizationStillNeeded(storedLocally, deviceCount) {
    return deviceCount > 0 ? !1 : !storedLocally;
  }

  // src/client.ts
  var clientModuleId = "@blue-soda/dsh-remote", pendingWorkspaceSelectionKey = "dsh-remote:pending-workspace-selection", deepSeekWorkspaceIcon = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAAACVBMVEUAAADy8vXx8fUsA3vyAAAAAXRSTlMAQObYZgAAAOxJREFUWMPtlsEOwyAMQxP+/6OnTZMGxHGcot3wDYgfhkJbs6urprzveGtu9d1NgEdto8w93GuA7zPS2RFg7ynsCOAokbtAmLr2YaeKgJ6fxpT8jCAC0qSqPyNoO5DXdvyouj4ClFAdYaDxK09uiBDCIYBf6CxLesnR0uF2pG+JmhCqrUb0AOvjjoQaYDTCEAAgMg5grhAeAQIB+/M15IcKlnUIp4CkTCd0AKb4zVsRSJVGYEVzT0agK107IMGMEurvromEpaX4wzk/IKw378kq4LafED6Oowxfk7AP2q8W16EdM3p29Eyvrv6vF0WIBfBziKyCAAAAAElFTkSuQmCC", gptWorkspaceIcon = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAAAclBMVEX////v7+++wMf////s7O3k5OTExcrX2Ny6vMTv8PPW19yXmqRqboJmaHd3eoaqrLPs7fGMjpfb3eKlpq1eYXOxs7pWWWd+gY5HSVdRVGVub3k+QU74+Pg4OkcvMT1ISlUoKjUiIy4fICueoKkaGyUSFB21Bp+qAAAAAXRSTlMAQObYZgAABChJREFUWMPtl916qjoQhlsdEwIECEgMYCBxx/u/xT1DrFUL1LO1DtY8LSLJvPnmB4gfH//sr7TPDfvdebf/xXZbkE/4zZ0MVhGHd9zJDsv+7F3//Z69tT5wljAu3tXw+TIlzXKJlhflYmJ+5uF5WpWhb54X+C/zZCmT2wJA4dJZwus0UYjIGqWyMhVbEp4yKNRRqlv0opW5PJLJ4rFJXvP4JKDRMqturBZjkMVJFYjR6iHQDQDI4+k2k51wZbWrqwp4mR/1CVYATyko9TFGxBUqL5IvMbXSWt0T8bkGgFLqgqZBI/Uxbx5UAxLaNcCNLBpttM7wbCc1pmJOpWAsDtey68UaQMyrFgb9uxLPjqg3+nGluyKN0XUdWw0BJ0NvTFFqgzpr9J+Dh4w0Ga1qkqBNswagxdRgFPAbgHRQG5jumGWyMxrTIaRRa4AzFs0YrNMXgI4MJWnsKYFhGNMnIh/mBOPsV8CZBFjN9w8ArowxnZzDr5IcvxTaKpxJCVsoI3SWysz1UM4AqQdzlCgho1IKrK+x1mKFzisANtgEU8E7GwHDgOqxe4w9tnM166wbaA2xBMBLrTWcAGYGdCiYVUJUrMCF+7l6gklrW1RwPi8ByjuActANqhKzVWoYrFGcCFzbDgNaBrTWpncFQMcI2DfWoAiDqRCiHcYM/ZdCEKkZqfR8spj4F0CiJjv2gHL6URNoCQDaYacL6O1oFDO2eQDUFP7AKdCRdC4CRDZaNifbjA41l/uYb/SZMAFNTBGzY7sG4J2jTsJgToOzIzXF3HZqBpSOAGduXbkGEKV1OqVGqRJpRxtvwbSw5IqAiUJItwBCOT9R32FCGo3nCkAN3t0UTBRC6zDONcBeOee9pr4T+ASbnDOdc5OmtREwEEB5w8Wqgt4bOV5c7Lt9iiK8zdP/IsBbutUGX4gNwEVCqd3FUt/xYnRWt7BvPBYQjwjAGXa3ASguHVAZ/WXKlA3eZDX1wRegFsr7E2wAlB9xFbx/7MV70kFT8aqNgLL3F8nFBmA3hrn/IJHjKJOK2oj1WI4Z4FzwN/81AOhgYD6v2xbIvaZIurLC9AfvB1WLTYAoXejF1xeBL5cpBIyEbmxkZymIXwDQh1BAfOrhI1lfwtgz8tozF5T4ttf3+32AyxC6klwAgw/YVTHpoK82xYt3wLP/Bw4AAI4D7/01TLKXBj9NEz2wEuGiKpoy68S/FwCLMQAZ3gXhihaCa25vQo6RyToOz9PE6wYjJgGi1YnqZX8qMIK+TDlnargGzSv4NvFjlwXf/nfLLMrAB4O/Xl3BH0d+RIASFgDA8IYMIXgr2+cBsbDdPSwAAHjSZFmb3txETCKIxc0uWwI8rxsLJcTiVndNwyMgKljbbNN2/zcEAja2+4RgIDYN2Bu/WtAOaEodnu2dnzz/7I/Y/w/LaEcX/MdfAAAAAElFTkSuQmCC", ENABLED_QR_PROVIDERS = ["github"], QR_LOGIN_ENABLED = !1, defaultQrProvider = ENABLED_QR_PROVIDERS[0] ?? "wechat";
  function isEnabledQrProvider(provider) {
    return ENABLED_QR_PROVIDERS.includes(provider);
  }
  function storedWorkspaceSelection() {
    let raw = window.sessionStorage.getItem(pendingWorkspaceSelectionKey);
    if (raw !== null)
      try {
        let value = JSON.parse(raw);
        if (typeof value.targetDeviceId != "string" || typeof value.workspaceId != "string") throw new Error("invalid");
        if (value.backend !== void 0 && value.backend !== "harness" && value.backend !== "codex") throw new Error("invalid");
        if (value.sessionId !== void 0 && typeof value.sessionId != "string") throw new Error("invalid");
        return value;
      } catch {
        window.sessionStorage.removeItem(pendingWorkspaceSelectionKey);
        return;
      }
  }
  function workspacesReady(snapshot) {
    return snapshot.baselinesReady === !0 || snapshot.phase === "ready";
  }
  var localeNamespace = "ds-harness-remote", en = {
    terminalRemote: "Remote terminal",
    terminalRemoteHint: "Allow trusted Remote devices to run an interactive shell as this Host user. On by default; independent of Agent approvals. Toggling saves and applies immediately.",
    terminalRemoteUnavailable: "Remote terminal is unavailable. Enable Remote terminal in the Host Remote settings; the switch applies immediately.",
    previewPorts: "Remote preview ports",
    previewPortsHint: "Allow specific Host ports (1024\u201365535), separated by commas. Empty disables preview. HTTP, WebSocket and hot reload are supported.",
    developmentSave: "Save access settings",
    developmentSaved: "Remote preview ports saved. Changes apply immediately.",
    terminalSaved: "Remote terminal setting saved. Applies immediately.",
    previewOpen: "Preview service",
    previewPort: "Host port",
    previewLocalOnly: "Preview requires Desktop or a browser connected to Harness on this computer.",
    pluginTitle: "DeepSeek Remote",
    pluginDescription: "Connect once. Available anytime.",
    expandSettings: "Show settings: {name}",
    collapseSettings: "Hide settings: {name}",
    unsaved: "Unsaved",
    associated: "Authorized",
    authorizationComplete: "Authorization complete",
    loadingSettings: "Loading DeepSeek Remote settings\u2026",
    mode: "Mode",
    pluginMode: "Plugin mode",
    host: "Host",
    client: "Client",
    authorization: "Authorization",
    account: "Account",
    hostRegistrationCode: "One-time device authorization code",
    ownedDeviceAuthorization: "Owned device",
    authorizedOn: "{role} is authorized on {serverUrl}.",
    readOnly: "This DSH profile does not provide writable user settings.",
    discard: "Discard",
    save: "Save",
    saving: "Saving\u2026",
    signOut: "Sign out",
    signingOut: "Signing out\u2026",
    hostName: "Host name",
    deviceId: "Device ID",
    serverUrl: "Server URL",
    serverUrlHint: "HTTPS origin used for account authorization and encrypted relay.",
    serverSaved: "Server address saved. Restart DSH to apply it.",
    codexRemote: "Codex Remote",
    codexStatusLine: "Current state: {state}",
    codexControlAllow: "Allow other users to control Codex on this device",
    codexSaved: "Codex Remote setting saved. Restart DSH to apply it.",
    codexBinaryLabel: "Codex command or path",
    codexBinaryMissing: 'No Codex found automatically. Install the Codex desktop app, or enter the path to a Codex CLI that supports "codex app-server".',
    codexBinaryConfirm: "Confirm",
    codexBinaryAuto: "Use auto-discovery",
    codexBinaryPlaceholder: 'Path to a Codex CLI that supports "codex app-server"',
    codexBinaryPinned: "Set by hand. Clear the field and confirm to discover it automatically again.",
    codexUnavailableAccount: "Codex cannot reach your account from this network (region not supported, or the sign-in expired). It returns as soon as the account can be refreshed.",
    codexUnavailableSignIn: "Codex is not signed in on this host. Sign in with the Codex app, then restart DSH.",
    codexUnavailableBinary: "The Codex CLI could not be started here. Install the Codex desktop app, or set the command or full path in the field below.",
    codexUnavailableGeneric: "Codex is unavailable on this host ({code}).",
    codexDisabled: "Codex is switched off on this device, so nobody can use it remotely.",
    codexStarting: "Starting the Codex connection...",
    codexUnavailableNoCode: "Codex is unavailable on this host.",
    codexRecheck: "Re-check",
    codexRemoteOff: "Codex is switched off on that device.",
    codexRemoteNotProvided: "That device does not provide Codex.",
    codexRemoteUnavailable: "Codex is currently unavailable on that device.",
    authorizeFromRemote: "Sign in from the Remote entry in the sidebar, then return here to manage this device.",
    authorizationMethod: "Authorization method",
    accountPassword: "Account password",
    registrationCode: "Device authorization code",
    registrationCodeHint: "Generate it after signing in on the Server website. Use it once to connect this device.",
    accountHint: "The account must belong to the selected Server.",
    password: "Password",
    passwordHint: "Used only for this HTTPS authorization request and never saved.",
    modeSavedNeedsAuthorization: "Mode saved. Authorize {role} before connecting. Existing registrations were kept.",
    modeSavedReused: "Mode saved. Existing registration reused. Restart Harness to apply.",
    modeSavedOwnedRole: "Mode saved. This owned device was authorized automatically. Restart Harness to apply.",
    enterRegistrationCode: "Enter the device authorization code.",
    enterAccountPassword: "Enter the Server account and password.",
    associationSaved: "Associated. Restart Harness to apply.",
    signedOut: "Signed out. Restart Harness to disconnect this mode.",
    signedOutDeepseek: "Signed out, and your DSH DeepSeek account was signed out too.",
    remoteRequestFailed: "Remote mode request failed.",
    remoteControlUnavailable: "Remote plugin control is still starting. Restart DSH if it stays unavailable.",
    switchTarget: "Switch Local / Remote Harness target",
    harnessTarget: "Harness target",
    close: "Close",
    refreshRemote: "Refresh remote hosts",
    refreshRemoteShort: "Refresh",
    local: "Local",
    remoteTarget: "Remote \xB7 {name}",
    thisMachineLocal: "This machine (Local)",
    currentDevice: "Current device",
    noRemoteHosts: "No authorized remote Host for this account.",
    online: "Online",
    offline: "Offline",
    connectionControlDisabled: 'That device is not accepting control. Turn on "Allow control" on it.',
    connectionHostOffline: "The selected Host is offline.",
    hostRefusesControl: "Not accepting control",
    thisMachineHost: "This machine as Remote Host",
    connected: "Connected",
    connectedAs: "Connected as {account}",
    connection: "Connection",
    checkingConnection: "Checking connection\u2026",
    connecting: "Connecting",
    reconnecting: "Reconnecting",
    lastActive: "Last active: {time}",
    neverConnected: "No successful connection yet.",
    reconnect: "Reconnect",
    reconnectingAction: "Reconnecting\u2026",
    reconnectStarted: "Reconnect requested.",
    connectionAuthorizationExpired: "Authorization expired. Sign in again to authorize this device.",
    connectionReplaced: "Another instance is using this Host identity. Standing by: retrying every five minutes, and taking over as soon as it leaves.",
    connectionCredentialsBusy: "Credential refresh is locked. Stop other instances. After a crash, stop all instances before removing server-credentials.json.refresh-lock and authorizing again.",
    connectionDeviceRevoked: "This device was removed on the Server. Sign in again to add it back.",
    connectionOwnershipRequired: "The Server no longer recognizes this Host as an owned device.",
    connectionRateLimited: "The Server is receiving too many requests. Automatic retry will continue.",
    connectionVersionMismatch: "The Plugin and Server protocol versions are incompatible.",
    connectionInvalidResponse: "The Server returned an invalid control message.",
    connectionReachability: "Cannot reach the Server. Check the network and Server address.",
    connectionUnexpected: "The connection stopped unexpectedly. Automatic retry will continue.",
    hostSignInHint: "Sign in to authorize this Host on the selected Server.",
    checkingHost: "Checking Host registration\u2026",
    hostUnavailable: "Host unavailable: {error}",
    serverAccountEmail: "Server account email",
    serverAccountPassword: "Server account password",
    signInRegisterHost: "Sign in and register Host",
    signingIn: "Signing in\u2026",
    useRegistrationCode: "Use connection code",
    registering: "Registering\u2026",
    remoteEntry: "Remote",
    remoteTitle: "Open a remote workspace",
    remoteDescription: "Choose one of your Hosts, then select a working directory. The Harness interface stays on this device.",
    chooseHost: "Host",
    chooseDirectory: "Working directory",
    selectHostHint: "Select an online Host to browse its directories.",
    emptyDirectory: "This directory has no visible subdirectories.",
    openWorkspace: "Open workspace",
    openingWorkspace: "Opening\u2026",
    loadingDirectory: "Loading directories\u2026",
    remoteProgressCheckingHost: "Checking Host",
    remoteProgressCheckingHostDetail: "Finding the selected device and checking whether it is online.",
    remoteProgressAuthorizingPeer: "Verifying authorization",
    remoteProgressAuthorizingPeerDetail: "Confirming account membership and pinned Host identity.",
    remoteProgressOpeningChannel: "Opening encrypted channel",
    remoteProgressOpeningChannelDetail: "Trying LAN, P2P, TURN, then Relay if needed.",
    remoteProgressProbeLan: "Probing LAN",
    remoteProgressProbeLanDetail: "Checking whether the Host is reachable on the local network.",
    remoteProgressProbeP2p: "Probing P2P",
    remoteProgressProbeP2pDetail: "Checking direct internet candidates between this device and the Host.",
    remoteProgressProbeTurn: "Probing TURN",
    remoteProgressProbeTurnDetail: "Checking the TURN relay path for restricted networks.",
    remoteProgressProbeRelay: "Preparing Relay",
    remoteProgressProbeRelayDetail: "Preparing the encrypted Server Relay fallback if direct paths do not open.",
    remoteProgressTryingPrefix: "Trying ",
    remoteProgressUsingPrefix: "Using ",
    remoteProgressLoadingWorkspaces: "Loading workspaces",
    remoteProgressLoadingWorkspacesDetail: "Reading the remote Harness workspace list through the tunnel.",
    remoteProgressSwitchingWorkspace: "Switching interface",
    remoteProgressSwitchingWorkspaceDetail: "Handing the remote workspace to the local Harness UI.",
    remoteProgressReady: "Ready",
    remoteProgressReadyDetail: "The remote Host is connected and encrypted.",
    backToHosts: "Choose another Host",
    currentDirectory: "Selected directory",
    directoryTruncated: "Only part of this directory could be shown.",
    pluginVersion: "Plugin {version}",
    harnessVersion: "Harness {version}",
    existingWorkspaces: "Existing workspaces",
    remotePathPlaceholder: "/home/user/project",
    remotePathHint: "Enter an absolute directory path on the selected Host.",
    noRemoteWorkspaces: "No remote workspaces yet. Use + to add one.",
    activeRemote: "{name}",
    exitRemote: "Exit",
    stopReconnecting: "Stop reconnecting",
    addRemoteWorkspace: "Add remote workspace",
    addCodexWorkspace: "Add Codex workspace",
    noCodexWorkspaces: "No Codex workspaces yet.",
    cancelAddWorkspace: "Cancel",
    confirmAddWorkspace: "Add and open",
    showAllWorkspaces: "Show all DSH workspaces",
    showAllCodexWorkspaces: "Show all Codex workspaces",
    remoteModeDsh: "Remote DSH \xB7 ",
    remoteModeCodex: "Remote Codex \xB7 ",
    remoteNetworkP2p: "P2P",
    remoteNetworkTurn: "TURN",
    remoteNetworkRelay: "Relay",
    remoteNetworkLan: "LAN",
    remoteNetworkOffline: "Disconnected",
    remoteLinkEncrypted: "End-to-end encrypted",
    connectionRouteTitle: "Connection route",
    connectionRouteFrom: "From",
    connectionRouteVia: "Via",
    connectionRouteTo: "To",
    connectionRouteCurrentDevice: "This device",
    connectionRouteLan: "Local network",
    connectionRouteP2p: "Direct internet path",
    connectionRouteTurn: "TURN relay service",
    connectionRouteRelay: "Remote Server",
    connectionRouteHost: "Work computer running Harness",
    connectionRouteLanDetail: "Direct transfer over the local network",
    connectionRouteP2pDetail: "Direct transfer over the internet",
    connectionRouteTurnDetail: "Encrypted transfer through the TURN service",
    connectionRouteRelayDetail: "Encrypted transfer through the Remote Server",
    connectionRouteEncrypted: "Application data remains end-to-end encrypted along this route.",
    connectionDetailsConnection: "Connection",
    connectionDetailsWebRtc: "Network details \xB7 WebRTC / ICE",
    connectionId: "Connection ID",
    connectedAt: "Established",
    preferredTransports: "Attempt order",
    controlChannel: "Control channel",
    controlAddress: "Control address",
    controlStateConnecting: "Connecting",
    controlStateOpen: "Connected",
    controlStateClosing: "Closing",
    controlStateClosed: "Closed",
    peerState: "Peer connection",
    dataChannel: "DataChannel",
    localCandidate: "Local candidate",
    remoteCandidate: "Remote candidate",
    localAddress: "Local address",
    remoteAddress: "Remote address",
    networkProtocol: "Network protocol",
    relayProtocol: "TURN protocol",
    roundTripTime: "Round-trip time",
    availableBitrate: "Available outgoing bitrate",
    bytesSent: "WebRTC bytes sent",
    bytesReceived: "WebRTC bytes received",
    notProvided: "Not provided",
    candidateHost: "Local address \xB7 host",
    candidateSrflx: "Public address \xB7 srflx",
    candidatePrflx: "Peer address \xB7 prflx",
    candidateRelay: "TURN address \xB7 relay",
    openLocalWorkspaces: "Open local workspaces",
    clientSignInHint: "Sign in to this Server to list your remote Hosts.",
    deepseekSignIn: "Sign in with DeepSeek account",
    deepseekSignInRequesting: "Requesting the DeepSeek sign-in page\u2026",
    deepseekSignInWaiting: "Finish signing in to DeepSeek in the browser.",
    deepseekSignInOpen: "Open the sign-in page",
    deepseekSignInTimeout: "DeepSeek sign-in was not completed. Try again.",
    deepseekAuthorizeUnavailable: "DeepSeek did not return a sign-in page. Try again.",
    changeServerUrl: "Change address",
    startSignIn: "Start sign-in",
    allowControlCurrentDevice: "Allow control of this device",
    allowControlDevice: "Allow control of device",
    connectedClientCount: "{count} connected",
    checkAcp: "Check ACP",
    checkingAcp: "Checking ACP\u2026",
    acpAvailable: "ACP available",
    acpUnavailable: "ACP unavailable",
    acpHint: "Connect compatible coding agents through the Agent Client Protocol.",
    acpCheckPassed: "Check passed",
    acpCheckFailed: "Check failed",
    addAcp: "Add ACP",
    acpName: "Service name",
    acpCommand: "Command",
    acpArguments: "Arguments",
    removeAcp: "Remove",
    currentConnectedDevices: "Currently connected devices",
    noConnectedClients: "No devices are currently connected to this Host.",
    unknownDevice: "Unknown device",
    exitRemoteAccount: "Sign out",
    revokeThisDevice: "Remove this device",
    revokeDeviceImpact: "Removes this device from the account. Signing in again brings it back with the same device id.",
    cancel: "Cancel",
    signOutImpact: "Signing out disconnects this plugin and also signs DSH out of your DeepSeek account.",
    wechatLogin: "WeChat QR",
    githubLogin: "GitHub QR",
    zhihuLogin: "Zhihu QR",
    scanWithWeChat: "Scan to continue with WeChat",
    scanWithGitHub: "Scan to continue with GitHub",
    scanWithZhihu: "Scan to continue with Zhihu",
    openInBrowser: "Continue in browser",
    scanLoginHint: "Authorize on your phone. This window will continue automatically.",
    currentServiceAddress: "Current service address:",
    accountPasswordLogin: "Password",
    qrLoginExpired: "This QR code expired. Refresh it to continue.",
    refreshQrCode: "Refresh QR code",
    codexVirtualWorkspace: "Codex virtual workspace",
    codexVirtualSessions: "Sessions"
  }, zh = {
    terminalRemote: "\u8FDC\u7A0B\u7EC8\u7AEF",
    terminalRemoteHint: "\u5141\u8BB8\u53D7\u4FE1\u4EFB\u7684 Remote \u8BBE\u5907\u4EE5\u6B64 Host \u7528\u6237\u8EAB\u4EFD\u8FD0\u884C\u4EA4\u4E92\u5F0F Shell\u3002\u9ED8\u8BA4\u5F00\u542F\uFF0C\u72EC\u7ACB\u4E8E Agent \u5BA1\u6279\uFF1B\u5207\u6362\u540E\u7ACB\u5373\u4FDD\u5B58\u5E76\u751F\u6548\u3002",
    terminalRemoteUnavailable: "\u8FDC\u7A0B\u7EC8\u7AEF\u4E0D\u53EF\u7528\u3002\u8BF7\u5728 Host \u7684 Remote \u8BBE\u7F6E\u4E2D\u5F00\u542F\u300C\u8FDC\u7A0B\u7EC8\u7AEF\u300D\uFF0C\u5F00\u5173\u4F1A\u7ACB\u5373\u751F\u6548\u3002",
    previewPorts: "\u8FDC\u7A0B\u9884\u89C8\u7AEF\u53E3",
    previewPortsHint: "\u586B\u5199\u5141\u8BB8\u8BBF\u95EE\u7684 Host \u7AEF\u53E3\uFF081024\u201365535\uFF09\uFF0C\u4EE5\u9017\u53F7\u5206\u9694\u3002\u7559\u7A7A\u7981\u7528\u9884\u89C8\uFF1B\u652F\u6301 HTTP\u3001WebSocket \u548C\u70ED\u66F4\u65B0\u3002",
    developmentSave: "\u4FDD\u5B58\u8BBF\u95EE\u8BBE\u7F6E",
    developmentSaved: "\u8FDC\u7A0B\u9884\u89C8\u7AEF\u53E3\u5DF2\u4FDD\u5B58\uFF0C\u7ACB\u5373\u751F\u6548\u3002",
    terminalSaved: "\u8FDC\u7A0B\u7EC8\u7AEF\u8BBE\u7F6E\u5DF2\u4FDD\u5B58\uFF0C\u7ACB\u5373\u751F\u6548\u3002",
    previewOpen: "\u9884\u89C8\u670D\u52A1",
    previewPort: "Host \u7AEF\u53E3",
    previewLocalOnly: "\u9884\u89C8\u9700\u8981 Desktop\uFF0C\u6216\u8FDE\u63A5\u5230\u672C\u673A Harness \u7684\u6D4F\u89C8\u5668\u3002",
    pluginTitle: "DeepSeek \u8FDC\u7A0B\u8FDE\u63A5",
    pluginDescription: "\u4E00\u6B21\u8FDE\u63A5\uFF0C\u968F\u65F6\u53EF\u7528\u3002",
    expandSettings: "\u5C55\u5F00\u8BBE\u7F6E\uFF1A{name}",
    collapseSettings: "\u6536\u8D77\u8BBE\u7F6E\uFF1A{name}",
    unsaved: "\u672A\u4FDD\u5B58",
    associated: "\u5DF2\u6388\u6743",
    authorizationComplete: "\u5DF2\u5B8C\u6210\u6388\u6743",
    loadingSettings: "\u6B63\u5728\u52A0\u8F7D DeepSeek \u8FDC\u7A0B\u8FDE\u63A5\u8BBE\u7F6E\u2026",
    mode: "\u6A21\u5F0F",
    pluginMode: "\u63D2\u4EF6\u6A21\u5F0F",
    host: "\u4E3B\u673A",
    client: "Client",
    authorization: "\u6388\u6743",
    account: "\u8D26\u53F7",
    hostRegistrationCode: "\u4E00\u6B21\u6027\u8BBE\u5907\u6388\u6743\u7801",
    ownedDeviceAuthorization: "\u81EA\u6709\u8BBE\u5907",
    authorizedOn: "{role}\u5DF2\u7ECF\u5728 {serverUrl} \u5B8C\u6210\u6388\u6743\u3002",
    readOnly: "\u6B64 DSH profile \u4E0D\u63D0\u4F9B\u53EF\u5199\u7684\u7528\u6237\u8BBE\u7F6E\u3002",
    discard: "\u653E\u5F03\u4FEE\u6539",
    save: "\u4FDD\u5B58",
    saving: "\u4FDD\u5B58\u4E2D\u2026",
    signOut: "\u9000\u51FA\u6388\u6743",
    signingOut: "\u6B63\u5728\u9000\u51FA\u2026",
    hostName: "\u4E3B\u673A\u540D\u79F0",
    deviceId: "\u8BBE\u5907 ID",
    serverUrl: "Server \u5730\u5740",
    serverUrlHint: "\u7528\u4E8E\u8D26\u53F7\u6388\u6743\u548C\u52A0\u5BC6\u4E2D\u7EE7\u7684 HTTPS \u5730\u5740\u3002",
    serverSaved: "Server \u5730\u5740\u5DF2\u4FDD\u5B58\uFF0C\u91CD\u542F DSH \u540E\u751F\u6548\u3002",
    codexRemote: "Codex Remote",
    codexStatusLine: "\u5F53\u524D\u72B6\u6001\uFF1A{state}",
    codexControlAllow: "\u5141\u8BB8\u5176\u4ED6\u7528\u6237\u63A7\u5236\u672C\u8BBE\u5907Codex",
    codexSaved: "Codex Remote \u8BBE\u7F6E\u5DF2\u4FDD\u5B58\uFF0C\u91CD\u542F DSH \u540E\u751F\u6548\u3002",
    codexBinaryLabel: "Codex \u547D\u4EE4\u6216\u8DEF\u5F84",
    codexBinaryMissing: '\u672A\u81EA\u52A8\u53D1\u73B0 Codex\u3002\u8BF7\u5B89\u88C5 Codex \u684C\u9762\u5E94\u7528\uFF0C\u6216\u586B\u5199\u652F\u6301 "codex app-server" \u7684 Codex CLI \u8DEF\u5F84\u3002',
    codexBinaryConfirm: "\u786E\u8BA4",
    codexBinaryAuto: "\u6062\u590D\u81EA\u52A8\u53D1\u73B0",
    codexBinaryPlaceholder: '\u652F\u6301 "codex app-server" \u7684 Codex CLI \u8DEF\u5F84',
    codexBinaryPinned: "\u5F53\u524D\u4E3A\u624B\u52A8\u6307\u5B9A\uFF1B\u6E05\u7A7A\u540E\u786E\u8BA4\u5373\u53EF\u6062\u590D\u81EA\u52A8\u53D1\u73B0\u3002",
    codexUnavailableAccount: "Codex \u65E0\u6CD5\u4ECE\u5F53\u524D\u7F51\u7EDC\u8BBF\u95EE\u4F60\u7684\u8D26\u53F7\uFF08\u533A\u57DF\u4E0D\u53D7\u652F\u6301\uFF0C\u6216\u767B\u5F55\u5DF2\u8FC7\u671F\uFF09\u3002\u8D26\u53F7\u6062\u590D\u53EF\u8FBE\u540E Codex \u5373\u56DE\u6765\u3002",
    codexUnavailableSignIn: "\u8FD9\u53F0 Host \u4E0A\u7684 Codex \u5C1A\u672A\u767B\u5F55\u3002\u8BF7\u5148\u7528 Codex \u5E94\u7528\u767B\u5F55\uFF0C\u7136\u540E\u91CD\u542F DSH\u3002",
    codexUnavailableBinary: "\u8FD9\u91CC\u7684 Codex CLI \u65E0\u6CD5\u542F\u52A8\u3002\u8BF7\u5B89\u88C5 Codex \u684C\u9762\u5E94\u7528\uFF0C\u6216\u5728\u4E0B\u65B9\u586B\u5199\u53EF\u7528\u7684\u547D\u4EE4\u6216\u5B8C\u6574\u8DEF\u5F84\u3002",
    codexUnavailableGeneric: "\u8FD9\u53F0 Host \u4E0A\u7684 Codex \u5F53\u524D\u4E0D\u53EF\u7528\uFF08{code}\uFF09\u3002",
    codexDisabled: "\u672C\u8BBE\u5907\u7684 Codex \u5DF2\u5173\u95ED\uFF0C\u5176\u4ED6\u7528\u6237\u65E0\u6CD5\u8FDC\u7A0B\u4F7F\u7528\u3002",
    codexStarting: "\u6B63\u5728\u542F\u52A8 Codex \u8FDE\u63A5\u2026",
    codexUnavailableNoCode: "\u8FD9\u53F0 Host \u4E0A\u7684 Codex \u5F53\u524D\u4E0D\u53EF\u7528\u3002",
    codexRecheck: "\u91CD\u65B0\u68C0\u67E5",
    codexRemoteOff: "\u8BE5\u8BBE\u5907\u7684 Codex \u5DF2\u5173\u95ED\u3002",
    codexRemoteNotProvided: "\u8BE5\u8BBE\u5907\u4E0D\u63D0\u4F9B Codex\u3002",
    codexRemoteUnavailable: "\u8BE5\u8BBE\u5907\u7684 Codex \u5F53\u524D\u4E0D\u53EF\u7528\u3002",
    authorizeFromRemote: "\u8BF7\u4ECE\u4FA7\u680F Remote \u5165\u53E3\u767B\u5F55\uFF0C\u767B\u5F55\u540E\u53EF\u5728\u8FD9\u91CC\u7BA1\u7406\u5F53\u524D\u8BBE\u5907\u3002",
    authorizationMethod: "\u6388\u6743\u65B9\u5F0F",
    accountPassword: "\u8D26\u53F7\u5BC6\u7801",
    registrationCode: "\u8BBE\u5907\u6388\u6743\u7801",
    registrationCodeHint: "\u767B\u5F55 Server \u7F51\u9875\u540E\u751F\u6210\uFF0C\u7528\u4E00\u6B21\u5373\u53EF\u8FDE\u63A5\u8FD9\u53F0\u8BBE\u5907\u3002",
    accountHint: "\u8D26\u53F7\u5FC5\u987B\u5C5E\u4E8E\u6240\u9009 Server\u3002",
    password: "\u5BC6\u7801",
    passwordHint: "\u4EC5\u7528\u4E8E\u672C\u6B21 HTTPS \u6388\u6743\u8BF7\u6C42\uFF0C\u4E0D\u4F1A\u4FDD\u5B58\u3002",
    modeSavedNeedsAuthorization: "\u6A21\u5F0F\u5DF2\u4FDD\u5B58\u3002\u8FDE\u63A5\u524D\u8BF7\u5148\u6388\u6743 {role}\uFF1B\u5DF2\u6709\u6CE8\u518C\u4FE1\u606F\u5DF2\u4FDD\u7559\u3002",
    modeSavedReused: "\u6A21\u5F0F\u5DF2\u4FDD\u5B58\u5E76\u590D\u7528\u5DF2\u6709\u6CE8\u518C\u4FE1\u606F\u3002\u91CD\u542F Harness \u540E\u751F\u6548\u3002",
    modeSavedOwnedRole: "\u6A21\u5F0F\u5DF2\u4FDD\u5B58\uFF0C\u5E76\u5DF2\u81EA\u52A8\u6388\u6743\u6B64\u81EA\u6709\u8BBE\u5907\u3002\u91CD\u542F Harness \u540E\u751F\u6548\u3002",
    enterRegistrationCode: "\u8BF7\u8F93\u5165\u8BBE\u5907\u6388\u6743\u7801\u3002",
    enterAccountPassword: "\u8BF7\u8F93\u5165 Server \u8D26\u53F7\u548C\u5BC6\u7801\u3002",
    associationSaved: "\u5173\u8054\u6210\u529F\u3002\u91CD\u542F Harness \u540E\u751F\u6548\u3002",
    signedOut: "\u5DF2\u9000\u51FA\u6388\u6743\u3002\u91CD\u542F Harness \u540E\u5C06\u65AD\u5F00\u6B64\u6A21\u5F0F\u3002",
    signedOutDeepseek: "\u5DF2\u9000\u51FA\u6388\u6743\uFF0CDSH \u7684 DeepSeek \u8D26\u53F7\u4E5F\u5DF2\u4E00\u5E76\u9000\u51FA\u767B\u5F55\u3002",
    remoteRequestFailed: "\u8FDC\u7A0B\u6A21\u5F0F\u8BF7\u6C42\u5931\u8D25\u3002",
    remoteControlUnavailable: "Remote \u63D2\u4EF6\u63A7\u5236\u901A\u9053\u4ECD\u5728\u542F\u52A8\uFF1B\u5982\u679C\u4E00\u76F4\u4E0D\u53EF\u7528\uFF0C\u8BF7\u91CD\u542F DSH\u3002",
    switchTarget: "\u5207\u6362\u672C\u5730\u6216\u8FDC\u7A0B Harness",
    harnessTarget: "Harness \u76EE\u6807",
    close: "\u5173\u95ED",
    refreshRemote: "\u5237\u65B0\u8FDC\u7A0B\u4E3B\u673A",
    refreshRemoteShort: "\u5237\u65B0",
    local: "\u672C\u5730",
    remoteTarget: "\u8FDC\u7A0B \xB7 {name}",
    thisMachineLocal: "\u6B64\u8BBE\u5907\uFF08\u672C\u5730\uFF09",
    currentDevice: "\u5F53\u524D\u8BBE\u5907",
    noRemoteHosts: "\u6B64\u8D26\u53F7\u6CA1\u6709\u5DF2\u6388\u6743\u7684\u8FDC\u7A0B Host\u3002",
    online: "\u5728\u7EBF",
    offline: "\u79BB\u7EBF",
    connectionControlDisabled: "\u8BE5\u8BBE\u5907\u5F53\u524D\u4E0D\u63A5\u53D7\u63A7\u5236\uFF0C\u8BF7\u5728\u5B83\u4E0A\u9762\u6253\u5F00\u300C\u5141\u8BB8\u63A7\u5236\u300D\u3002",
    connectionHostOffline: "\u6240\u9009 Host \u5F53\u524D\u79BB\u7EBF\u3002",
    hostRefusesControl: "\u4E0D\u63A5\u53D7\u63A7\u5236",
    thisMachineHost: "\u5C06\u6B64\u8BBE\u5907\u4F5C\u4E3A\u8FDC\u7A0B Host",
    connected: "\u5DF2\u8FDE\u63A5",
    connectedAs: "\u5DF2\u4F7F\u7528 {account} \u8FDE\u63A5",
    connection: "\u8FDE\u63A5\u72B6\u6001",
    checkingConnection: "\u6B63\u5728\u68C0\u67E5\u8FDE\u63A5\u2026",
    connecting: "\u6B63\u5728\u8FDE\u63A5",
    reconnecting: "\u6B63\u5728\u91CD\u8FDE",
    lastActive: "\u6700\u540E\u6D3B\u8DC3\uFF1A{time}",
    neverConnected: "\u5C1A\u672A\u6210\u529F\u8FDE\u63A5\u8FC7\u3002",
    reconnect: "\u624B\u52A8\u91CD\u8FDE",
    reconnectingAction: "\u6B63\u5728\u91CD\u8FDE\u2026",
    reconnectStarted: "\u5DF2\u53D1\u8D77\u91CD\u8FDE\u3002",
    connectionAuthorizationExpired: "\u6388\u6743\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55\u4EE5\u91CD\u65B0\u6388\u6743\u6B64\u8BBE\u5907\u3002",
    connectionReplaced: "\u53E6\u4E00\u5B9E\u4F8B\u6B63\u5728\u4F7F\u7528\u6B64 Host \u8EAB\u4EFD\u3002\u672C\u673A\u5DF2\u8FDB\u5165\u5F85\u547D\uFF1A\u6BCF 5 \u5206\u949F\u91CD\u8BD5\u4E00\u6B21\uFF0C\u5BF9\u65B9\u9000\u51FA\u540E\u4F1A\u81EA\u52A8\u63A5\u7BA1\u3002",
    connectionCredentialsBusy: "\u51ED\u636E\u5237\u65B0\u88AB\u9501\u5B9A\uFF0C\u8BF7\u505C\u6B62\u5176\u4ED6\u5B9E\u4F8B\u3002\u82E5\u6B64\u524D\u5F02\u5E38\u9000\u51FA\uFF0C\u8BF7\u505C\u6B62\u6240\u6709\u5B9E\u4F8B\u540E\u79FB\u9664 server-credentials.json.refresh-lock \u5E76\u91CD\u65B0\u6388\u6743\u3002",
    connectionDeviceRevoked: "\u6B64\u8BBE\u5907\u5DF2\u5728 Server \u4E0A\u88AB\u79FB\u9664\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55\u4EE5\u91CD\u65B0\u6DFB\u52A0\u3002",
    connectionOwnershipRequired: "Server \u5DF2\u4E0D\u518D\u5C06\u6B64 Host \u8BC6\u522B\u4E3A\u5F53\u524D\u8D26\u53F7\u7684\u8BBE\u5907\u3002",
    connectionRateLimited: "Server \u8BF7\u6C42\u8FC7\u591A\uFF0C\u63D2\u4EF6\u5C06\u7EE7\u7EED\u81EA\u52A8\u91CD\u8BD5\u3002",
    connectionVersionMismatch: "Plugin \u4E0E Server \u7684\u534F\u8BAE\u7248\u672C\u4E0D\u517C\u5BB9\u3002",
    connectionInvalidResponse: "Server \u8FD4\u56DE\u4E86\u65E0\u6548\u7684\u63A7\u5236\u6D88\u606F\u3002",
    connectionReachability: "\u65E0\u6CD5\u8FDE\u63A5 Server\uFF0C\u8BF7\u68C0\u67E5\u7F51\u7EDC\u548C Server \u5730\u5740\u3002",
    connectionUnexpected: "\u8FDE\u63A5\u610F\u5916\u4E2D\u65AD\uFF0C\u63D2\u4EF6\u5C06\u7EE7\u7EED\u81EA\u52A8\u91CD\u8BD5\u3002",
    hostSignInHint: "\u767B\u5F55\u540E\u5728\u6240\u9009 Server \u4E0A\u6388\u6743\u6B64 Host\u3002",
    checkingHost: "\u6B63\u5728\u68C0\u67E5 Host \u6CE8\u518C\u72B6\u6001\u2026",
    hostUnavailable: "Host \u4E0D\u53EF\u7528\uFF1A{error}",
    serverAccountEmail: "Server \u8D26\u53F7\u90AE\u7BB1",
    serverAccountPassword: "Server \u8D26\u53F7\u5BC6\u7801",
    signInRegisterHost: "\u767B\u5F55\u5E76\u6CE8\u518C Host",
    signingIn: "\u6B63\u5728\u767B\u5F55\u2026",
    useRegistrationCode: "\u4F7F\u7528\u8FDE\u63A5\u7801",
    registering: "\u6B63\u5728\u6CE8\u518C\u2026",
    remoteEntry: "Remote",
    remoteTitle: "\u6253\u5F00\u8FDC\u7AEF\u5DE5\u4F5C\u533A",
    remoteDescription: "\u9009\u62E9\u60F3\u8981\u8FDE\u63A5\u4E3B\u673A\u548C\u5DE5\u4F5C\u76EE\u5F55\u3002",
    chooseHost: "\u4E3B\u673A",
    chooseDirectory: "\u5DE5\u4F5C\u76EE\u5F55",
    selectHostHint: "\u9009\u62E9\u4E00\u53F0\u5728\u7EBF\u4E3B\u673A\u4EE5\u6D4F\u89C8\u5176\u76EE\u5F55\u3002",
    emptyDirectory: "\u8FD9\u4E2A\u76EE\u5F55\u4E0B\u6CA1\u6709\u53EF\u89C1\u7684\u5B50\u76EE\u5F55\u3002",
    openWorkspace: "\u6253\u5F00\u5DE5\u4F5C\u533A",
    openingWorkspace: "\u6B63\u5728\u6253\u5F00\u2026",
    loadingDirectory: "\u6B63\u5728\u52A0\u8F7D\u76EE\u5F55\u2026",
    remoteProgressCheckingHost: "\u6B63\u5728\u68C0\u67E5 Host",
    remoteProgressCheckingHostDetail: "\u6B63\u5728\u67E5\u627E\u6240\u9009\u8BBE\u5907\u5E76\u786E\u8BA4\u662F\u5426\u5728\u7EBF\u3002",
    remoteProgressAuthorizingPeer: "\u6B63\u5728\u9A8C\u8BC1\u6388\u6743",
    remoteProgressAuthorizingPeerDetail: "\u6B63\u5728\u786E\u8BA4\u8D26\u53F7\u6210\u5458\u5173\u7CFB\u548C\u5DF2\u56FA\u5B9A\u7684 Host \u8EAB\u4EFD\u3002",
    remoteProgressOpeningChannel: "\u6B63\u5728\u5EFA\u7ACB\u52A0\u5BC6\u901A\u9053",
    remoteProgressOpeningChannelDetail: "\u4F9D\u6B21\u5C1D\u8BD5\u5C40\u57DF\u7F51\u3001P2P\u3001TURN\uFF0C\u5FC5\u8981\u65F6\u56DE\u843D\u5230 Relay\u3002",
    remoteProgressProbeLan: "\u6B63\u5728\u63A2\u6D4B\u5C40\u57DF\u7F51",
    remoteProgressProbeLanDetail: "\u68C0\u67E5\u5F53\u524D\u8BBE\u5907\u662F\u5426\u80FD\u901A\u8FC7\u672C\u5730\u7F51\u7EDC\u76F4\u8FDE Host\u3002",
    remoteProgressProbeP2p: "\u6B63\u5728\u63A2\u6D4B P2P",
    remoteProgressProbeP2pDetail: "\u68C0\u67E5\u5F53\u524D\u8BBE\u5907\u548C Host \u4E4B\u95F4\u7684\u4E92\u8054\u7F51\u76F4\u8FDE\u5019\u9009\u8DEF\u5F84\u3002",
    remoteProgressProbeTurn: "\u6B63\u5728\u63A2\u6D4B TURN",
    remoteProgressProbeTurnDetail: "\u68C0\u67E5\u53D7\u9650\u7F51\u7EDC\u4E0B\u53EF\u7528\u7684 TURN \u4E2D\u7EE7\u8DEF\u5F84\u3002",
    remoteProgressProbeRelay: "\u6B63\u5728\u51C6\u5907 Relay",
    remoteProgressProbeRelayDetail: "\u5982\u679C\u76F4\u8FDE\u8DEF\u5F84\u672A\u6253\u5F00\uFF0C\u5C06\u56DE\u843D\u5230\u52A0\u5BC6\u7684 Server Relay\u3002",
    remoteProgressTryingPrefix: "\u6B63\u5728\u5C1D\u8BD5 ",
    remoteProgressUsingPrefix: "\u5DF2\u8FDE\u63A5 ",
    remoteProgressLoadingWorkspaces: "\u6B63\u5728\u52A0\u8F7D\u5DE5\u4F5C\u533A",
    remoteProgressLoadingWorkspacesDetail: "\u901A\u8FC7\u96A7\u9053\u8BFB\u53D6\u8FDC\u7AEF Harness \u5DE5\u4F5C\u533A\u5217\u8868\u3002",
    remoteProgressSwitchingWorkspace: "\u6B63\u5728\u5207\u6362\u754C\u9762",
    remoteProgressSwitchingWorkspaceDetail: "\u6B63\u5728\u628A\u8FDC\u7AEF\u5DE5\u4F5C\u533A\u4EA4\u7ED9\u672C\u5730 Harness UI\u3002",
    remoteProgressReady: "\u5DF2\u5C31\u7EEA",
    remoteProgressReadyDetail: "\u8FDC\u7AEF Host \u5DF2\u8FDE\u63A5\uFF0C\u7AEF\u5230\u7AEF\u52A0\u5BC6\u5DF2\u5EFA\u7ACB\u3002",
    backToHosts: "\u9009\u62E9\u5176\u4ED6\u4E3B\u673A",
    currentDirectory: "\u5DF2\u9009\u76EE\u5F55",
    directoryTruncated: "\u76EE\u5F55\u5185\u5BB9\u8F83\u591A\uFF0C\u76EE\u524D\u53EA\u663E\u793A\u4E86\u4E00\u90E8\u5206\u3002",
    pluginVersion: "\u63D2\u4EF6 {version}",
    harnessVersion: "Harness {version}",
    existingWorkspaces: "\u5DF2\u6709\u5DE5\u4F5C\u533A",
    remotePathPlaceholder: "/home/user/project",
    remotePathHint: "\u8F93\u5165\u6240\u9009\u4E3B\u673A\u4E0A\u7684\u7EDD\u5BF9\u76EE\u5F55\u8DEF\u5F84\u3002",
    noRemoteWorkspaces: "\u8FD9\u53F0\u4E3B\u673A\u8FD8\u6CA1\u6709\u5DE5\u4F5C\u533A\uFF0C\u70B9\u51FB + \u6DFB\u52A0\u3002",
    activeRemote: "{name}",
    exitRemote: "\u9000\u51FA",
    stopReconnecting: "\u505C\u6B62\u91CD\u8FDE",
    addRemoteWorkspace: "\u6DFB\u52A0\u8FDC\u7A0B\u5DE5\u4F5C\u533A",
    addCodexWorkspace: "\u6DFB\u52A0 Codex \u5DE5\u4F5C\u533A",
    noCodexWorkspaces: "\u8FD8\u6CA1\u6709 Codex \u5DE5\u4F5C\u533A\u3002",
    cancelAddWorkspace: "\u53D6\u6D88",
    confirmAddWorkspace: "\u786E\u8BA4\u5E76\u6253\u5F00",
    showAllWorkspaces: "\u663E\u793A\u5168\u90E8 DSH \u5DE5\u4F5C\u533A",
    showAllCodexWorkspaces: "\u663E\u793A\u5168\u90E8 Codex \u5DE5\u4F5C\u533A",
    remoteModeDsh: "\u8FDC\u7A0BDSH \xB7 ",
    remoteModeCodex: "\u8FDC\u7A0BCodex \xB7 ",
    remoteNetworkP2p: "P2P",
    remoteNetworkTurn: "TURN",
    remoteNetworkRelay: "\u4E2D\u7EE7",
    remoteNetworkLan: "\u5C40\u57DF\u7F51",
    remoteNetworkOffline: "\u5DF2\u65AD\u5F00",
    remoteLinkEncrypted: "\u7AEF\u5230\u7AEF\u52A0\u5BC6",
    connectionRouteTitle: "\u8FDE\u63A5\u7EBF\u8DEF",
    connectionRouteFrom: "\u8D77\u70B9",
    connectionRouteVia: "\u7ECF\u8FC7",
    connectionRouteTo: "\u7EC8\u70B9",
    connectionRouteCurrentDevice: "\u5F53\u524D\u8BBE\u5907",
    connectionRouteLan: "\u540C\u4E00\u5C40\u57DF\u7F51",
    connectionRouteP2p: "\u4E92\u8054\u7F51\u76F4\u8FDE",
    connectionRouteTurn: "TURN \u4E2D\u7EE7\u670D\u52A1",
    connectionRouteRelay: "Remote Server",
    connectionRouteHost: "\u8FD0\u884C Harness \u7684\u5DE5\u4F5C\u7535\u8111",
    connectionRouteLanDetail: "\u5728\u672C\u5730\u7F51\u7EDC\u4E2D\u76F4\u63A5\u4F20\u8F93",
    connectionRouteP2pDetail: "\u901A\u8FC7\u4E92\u8054\u7F51\u76F4\u63A5\u4F20\u8F93",
    connectionRouteTurnDetail: "\u901A\u8FC7 TURN \u670D\u52A1\u8F6C\u53D1\u52A0\u5BC6\u6570\u636E",
    connectionRouteRelayDetail: "\u901A\u8FC7 Remote Server \u8F6C\u53D1\u52A0\u5BC6\u6570\u636E",
    connectionRouteEncrypted: "\u7EBF\u8DEF\u4E0A\u7684\u4E1A\u52A1\u6570\u636E\u4FDD\u6301\u7AEF\u5230\u7AEF\u52A0\u5BC6\u3002",
    connectionDetailsConnection: "\u8FDE\u63A5",
    connectionDetailsWebRtc: "\u7F51\u7EDC\u8BE6\u60C5 \xB7 WebRTC / ICE",
    connectionId: "\u8FDE\u63A5\u7F16\u53F7",
    connectedAt: "\u5EFA\u7ACB\u65F6\u95F4",
    preferredTransports: "\u5C1D\u8BD5\u987A\u5E8F",
    controlChannel: "\u63A7\u5236\u901A\u9053",
    controlAddress: "\u63A7\u5236\u5730\u5740",
    controlStateConnecting: "\u8FDE\u63A5\u4E2D",
    controlStateOpen: "\u5DF2\u8FDE\u63A5",
    controlStateClosing: "\u6B63\u5728\u5173\u95ED",
    controlStateClosed: "\u5DF2\u5173\u95ED",
    peerState: "\u8FDE\u63A5\u72B6\u6001",
    dataChannel: "DataChannel",
    localCandidate: "\u672C\u5730\u5019\u9009",
    remoteCandidate: "\u8FDC\u7AEF\u5019\u9009",
    localAddress: "\u672C\u5730\u5730\u5740",
    remoteAddress: "\u8FDC\u7AEF\u5730\u5740",
    networkProtocol: "\u4F20\u8F93\u534F\u8BAE",
    relayProtocol: "TURN \u534F\u8BAE",
    roundTripTime: "\u5F80\u8FD4\u65F6\u5EF6",
    availableBitrate: "\u53EF\u7528\u4E0A\u884C\u5E26\u5BBD",
    bytesSent: "WebRTC \u5DF2\u53D1\u9001",
    bytesReceived: "WebRTC \u5DF2\u63A5\u6536",
    notProvided: "\u672A\u63D0\u4F9B",
    candidateHost: "\u672C\u5730\u5730\u5740 \xB7 host",
    candidateSrflx: "\u516C\u7F51\u5730\u5740 \xB7 srflx",
    candidatePrflx: "\u5BF9\u7AEF\u5730\u5740 \xB7 prflx",
    candidateRelay: "TURN \u5730\u5740 \xB7 relay",
    openLocalWorkspaces: "\u6253\u5F00\u672C\u5730\u5DE5\u4F5C\u533A",
    clientSignInHint: "\u767B\u5F55 Server \u540E\u5373\u53EF\u67E5\u770B\u81EA\u5DF1\u7684\u8FDC\u7AEF\u4E3B\u673A\u3002",
    deepseekSignIn: "\u4F7F\u7528 DeepSeek \u8D26\u53F7\u767B\u5F55",
    deepseekSignInRequesting: "\u6B63\u5728\u83B7\u53D6 DeepSeek \u767B\u5F55\u9875\u2026",
    deepseekSignInWaiting: "\u8BF7\u5728\u6D4F\u89C8\u5668\u4E2D\u5B8C\u6210 DeepSeek \u767B\u5F55\u3002",
    deepseekSignInOpen: "\u624B\u52A8\u6253\u5F00\u767B\u5F55\u9875",
    deepseekSignInTimeout: "DeepSeek \u767B\u5F55\u672A\u5B8C\u6210\uFF0C\u8BF7\u91CD\u8BD5\u3002",
    deepseekAuthorizeUnavailable: "\u672A\u80FD\u83B7\u53D6 DeepSeek \u767B\u5F55\u9875\uFF0C\u8BF7\u91CD\u8BD5\u3002",
    changeServerUrl: "\u4FEE\u6539\u5730\u5740",
    startSignIn: "\u5F00\u59CB\u767B\u5F55",
    allowControlCurrentDevice: "\u5141\u8BB8\u63A7\u5236\u5F53\u524D\u8BBE\u5907",
    allowControlDevice: "\u5141\u8BB8\u63A7\u5236\u8BBE\u5907",
    connectedClientCount: "{count} \u53F0\u5DF2\u8FDE\u63A5",
    checkAcp: "\u68C0\u6D4B ACP",
    checkingAcp: "\u6B63\u5728\u68C0\u6D4B ACP\u2026",
    acpAvailable: "ACP \u53EF\u7528",
    acpUnavailable: "ACP \u4E0D\u53EF\u7528",
    acpHint: "\u901A\u8FC7 Agent Client Protocol \u8FDE\u63A5\u517C\u5BB9\u7684\u7F16\u7801 Agent\u3002",
    acpCheckPassed: "\u68C0\u6D4B\u901A\u8FC7",
    acpCheckFailed: "\u68C0\u6D4B\u5931\u8D25",
    addAcp: "\u6DFB\u52A0 ACP",
    acpName: "\u670D\u52A1\u540D\u79F0",
    acpCommand: "\u542F\u52A8\u547D\u4EE4",
    acpArguments: "\u547D\u4EE4\u53C2\u6570",
    removeAcp: "\u79FB\u9664",
    currentConnectedDevices: "\u5F53\u524D\u8FDE\u63A5\u7684\u8BBE\u5907",
    noConnectedClients: "\u76EE\u524D\u6CA1\u6709\u8BBE\u5907\u8FDE\u63A5\u5230\u8FD9\u53F0\u4E3B\u673A\u3002",
    unknownDevice: "\u672A\u77E5\u8BBE\u5907",
    exitRemoteAccount: "\u9000\u51FA\u8D26\u53F7",
    revokeThisDevice: "\u6CE8\u9500\u672C\u8BBE\u5907",
    revokeDeviceImpact: "\u5C06\u4ECE\u8D26\u53F7\u4E2D\u79FB\u9664\u672C\u8BBE\u5907\uFF1B\u91CD\u65B0\u767B\u5F55\u4F1A\u4EE5\u540C\u4E00\u4E2A\u8BBE\u5907 ID \u56DE\u6765\u3002",
    cancel: "\u53D6\u6D88",
    signOutImpact: "\u9000\u51FA\u540E\u4F1A\u65AD\u5F00\u672C\u63D2\u4EF6\u7684\u8FDE\u63A5\uFF0C\u5E76\u4E14\u4F1A\u4E00\u5E76\u9000\u51FA DSH \u7684 DeepSeek \u8D26\u53F7\u767B\u5F55\u3002",
    wechatLogin: "\u5FAE\u4FE1\u626B\u7801",
    githubLogin: "GitHub \u626B\u7801",
    zhihuLogin: "\u77E5\u4E4E\u626B\u7801",
    scanWithWeChat: "\u4F7F\u7528\u5FAE\u4FE1\u626B\u7801\u767B\u5F55",
    scanWithGitHub: "\u4F7F\u7528 GitHub \u626B\u7801\u767B\u5F55",
    scanWithZhihu: "\u4F7F\u7528\u77E5\u4E4E\u626B\u7801\u767B\u5F55",
    openInBrowser: "\u5728\u6D4F\u89C8\u5668\u4E2D\u7EE7\u7EED",
    scanLoginHint: "\u8BF7\u5728\u624B\u673A\u4E0A\u5B8C\u6210\u6388\u6743\uFF0C\u6B64\u7A97\u53E3\u4F1A\u81EA\u52A8\u7EE7\u7EED\u3002",
    currentServiceAddress: "\u5F53\u524D\u670D\u52A1\u5730\u5740\uFF1A",
    accountPasswordLogin: "\u8D26\u53F7\u5BC6\u7801",
    qrLoginExpired: "\u4E8C\u7EF4\u7801\u5DF2\u8FC7\u671F\uFF0C\u8BF7\u5237\u65B0\u540E\u91CD\u8BD5\u3002",
    refreshQrCode: "\u5237\u65B0\u4E8C\u7EF4\u7801",
    codexVirtualWorkspace: "Codex \u5DE5\u4F5C\u533A",
    codexVirtualSessions: "Sessions"
  };
  function codexUnavailableKey(code) {
    return code === "CODEX_ACCOUNT_UNREACHABLE" ? "codexUnavailableAccount" : code === "CODEX_AUTH_REQUIRED" ? "codexUnavailableSignIn" : code === "CODEX_BINARY_UNAVAILABLE" || code === "CODEX_START_TIMEOUT" ? "codexUnavailableBinary" : "codexUnavailableGeneric";
  }
  var defaultPreferredTransports = ["lan", "p2p", "turn", "relay"], controlRouteBackoffStepsMs = [1e3, 2e3, 5e3, 1e4, 3e4], ControlRouteUnavailableError = class extends Error {
    constructor(message) {
      super(message), this.name = "ControlRouteUnavailableError";
    }
  };
  function controlRouteUnavailableStatus() {
    return {
      mode: "local",
      available: !1,
      controlUnavailable: !0,
      connected: !1,
      transport: "Disconnected",
      remoteFeatures: { commandList: !1, fileViewer: !1, terminal: !1, codex: !1 },
      hostAuthorizationAvailable: !1
    };
  }
  function normalizedPreferredTransports(value) {
    return value === void 0 || value.length === 0 ? [...defaultPreferredTransports] : [...value];
  }
  function formatLocalTime(value) {
    let date = new Date(value);
    return Number.isNaN(date.getTime()) ? "\u2014" : date.toLocaleString();
  }
  function formatByteSize(value) {
    if (value < 1024) return `${value.toLocaleString()} B`;
    let units = ["KiB", "MiB", "GiB"], amount = value, unit = "B";
    for (let nextUnit of units)
      if (amount /= 1024, unit = nextUnit, amount < 1024) break;
    return `${amount.toLocaleString(void 0, { maximumFractionDigits: 1 })} ${unit}`;
  }
  function formatBitrate(value) {
    let units = ["bit/s", "Kbit/s", "Mbit/s", "Gbit/s"], amount = value, unitIndex = 0;
    for (; amount >= 1e3 && unitIndex < units.length - 1; )
      amount /= 1e3, unitIndex += 1;
    return `${amount.toLocaleString(void 0, { maximumFractionDigits: 1 })} ${units[unitIndex]}`;
  }
  function shortDeviceId(value) {
    return value.length <= 14 ? value : `${value.slice(0, 8)}\u2026${value.slice(-4)}`;
  }
  function transportLabel(value, t) {
    return t(value === "lan" ? "remoteNetworkLan" : value === "p2p" ? "remoteNetworkP2p" : value === "turn" ? "remoteNetworkTurn" : "remoteNetworkRelay");
  }
  function transportDiagnosticLabel(value) {
    return value === "lan" ? "LAN" : value === "p2p" ? "P2P" : value === "turn" ? "TURN" : "Relay";
  }
  function transportProgressCopy(value) {
    return value === "lan" ? { label: "remoteProgressProbeLan", detail: "remoteProgressProbeLanDetail" } : value === "p2p" ? { label: "remoteProgressProbeP2p", detail: "remoteProgressProbeP2pDetail" } : value === "turn" ? { label: "remoteProgressProbeTurn", detail: "remoteProgressProbeTurnDetail" } : { label: "remoteProgressProbeRelay", detail: "remoteProgressProbeRelayDetail" };
  }
  function statusTransportPreference(status) {
    if (status?.transport === "LAN") return "lan";
    if (status?.transport === "P2P") return "p2p";
    if (status?.transport === "TURN") return "turn";
    if (status?.transport === "Relay") return "relay";
  }
  function connectedProgress(status) {
    let activeTransport = statusTransportPreference(status);
    if (activeTransport !== void 0)
      return {
        label: "remoteProgressReady",
        detail: "remoteProgressReadyDetail",
        percent: 100,
        transports: normalizedPreferredTransports(status?.preferredTransports),
        activeTransports: [activeTransport],
        routeVerb: "using"
      };
  }
  function loadingProgressPercent(elapsedMs) {
    return elapsedMs >= 6800 ? 96 : elapsedMs >= 3800 ? 93 : elapsedMs >= 1600 ? 89 : 84;
  }
  function observedConnectionProgress(status, targetDeviceId, preferredTransports, connectedStep, connectedAt) {
    let transports = normalizedPreferredTransports(status.preferredTransports ?? preferredTransports), connection = status.connectionProgress;
    if (connection?.targetDeviceId === targetDeviceId) {
      if (connection.phase === "checking-host")
        return { label: "remoteProgressCheckingHost", detail: "remoteProgressCheckingHostDetail", percent: 12 };
      if (connection.phase === "authorizing-peer")
        return { label: "remoteProgressAuthorizingPeer", detail: "remoteProgressAuthorizingPeerDetail", percent: 30 };
      let activeTransports = connection.activeTransports?.filter((transport) => transports.includes(transport)) ?? [];
      if (connection.phase === "probing") {
        let activeIndex = Math.max(0, ...activeTransports.map((transport) => transports.indexOf(transport)));
        return {
          ...activeTransports.length === 1 ? transportProgressCopy(activeTransports[0]) : { label: "remoteProgressOpeningChannel", detail: "remoteProgressOpeningChannelDetail" },
          percent: Math.min(76, 42 + activeIndex * 10),
          transports,
          activeTransports,
          routeVerb: "trying"
        };
      }
      return {
        ...connectedStep,
        percent: loadingProgressPercent(connectedAt === void 0 ? 0 : Date.now() - connectedAt),
        transports,
        activeTransports,
        routeVerb: "using"
      };
    }
    if (status.connectedTargetDeviceId !== targetDeviceId) return;
    let activeTransport = statusTransportPreference(status);
    return {
      ...connectedStep,
      percent: loadingProgressPercent(connectedAt === void 0 ? 0 : Date.now() - connectedAt),
      transports,
      ...activeTransport === void 0 ? {} : { activeTransports: [activeTransport], routeVerb: "using" }
    };
  }
  function connectionErrorMessage(code, t) {
    return t(code === "ACCOUNT_AUTH_REQUIRED" || code === "AUTH_INVALID" || code === "TOKEN_EXPIRED" ? "connectionAuthorizationExpired" : code === "CONNECTION_REPLACED" ? "connectionReplaced" : code === "CONTROL_DISABLED" ? "connectionControlDisabled" : code === "HOST_OFFLINE" ? "connectionHostOffline" : code === "SERVER_CREDENTIALS_BUSY" ? "connectionCredentialsBusy" : code === "DEVICE_REVOKED" ? "connectionDeviceRevoked" : code === "DEVICE_OWNERSHIP_REQUIRED" ? "connectionOwnershipRequired" : code === "RATE_LIMITED" ? "connectionRateLimited" : code === "UNSUPPORTED_VERSION" ? "connectionVersionMismatch" : code === "INVALID_MESSAGE" ? "connectionInvalidResponse" : code === "CONNECTION_FAILED" || code === "SERVER_NOT_CONFIGURED" ? "connectionReachability" : "connectionUnexpected");
  }
  var localisedRemoteCodes = /* @__PURE__ */ new Set([
    "ACCOUNT_AUTH_REQUIRED",
    "AUTH_INVALID",
    "TOKEN_EXPIRED",
    "MEMBERSHIP_REQUIRED",
    "CONNECTION_REPLACED",
    "CONTROL_DISABLED",
    "HOST_OFFLINE",
    "SERVER_CREDENTIALS_BUSY",
    "DEVICE_REVOKED",
    "DEVICE_OWNERSHIP_REQUIRED",
    "RATE_LIMITED",
    "UNSUPPORTED_VERSION",
    "CONNECTION_FAILED",
    "SERVER_NOT_CONFIGURED",
    "CODEX_DISABLED",
    "CODEX_NOT_PROVIDED",
    "CODEX_UNAVAILABLE"
  ]);
  function controlFailure(error, t) {
    let code = error?.details?.remoteCode;
    return Object.assign(new Error(error?.message ?? t("remoteRequestFailed")), {
      ...code === void 0 ? {} : { code },
      retryable: error?.details?.retryable === !0
    });
  }
  function remoteErrorMessage(reason, t) {
    let code = reason instanceof Error && "code" in reason && typeof reason.code == "string" ? reason.code : void 0;
    if (code !== void 0) {
      if (code === "MEMBERSHIP_REQUIRED") return t("connectionOwnershipRequired");
      if (code === "CODEX_DISABLED") return t("codexRemoteOff");
      if (code === "CODEX_NOT_PROVIDED") return t("codexRemoteNotProvided");
      if (code === "CODEX_UNAVAILABLE") return t("codexRemoteUnavailable");
      if (localisedRemoteCodes.has(code)) return connectionErrorMessage(code, t);
    }
    return reason instanceof Error ? reason.message : String(reason);
  }
  async function setDeviceControl(control, enabled, authorized) {
    return enabled && !authorized ? await control("host.authorization.set", { enabled: !0 }) : await control("host.connection.set", { connected: enabled });
  }
  function connectionStatusLabel(status, t) {
    return status === void 0 ? t("checkingConnection") : status.online ? t("online") : status.reconnecting ? t(status.lastActiveAt === void 0 && status.error === void 0 ? "connecting" : "reconnecting") : t("offline");
  }
  function connectionStatusClass(status) {
    return status?.online ? " isOnline" : status?.reconnecting ? " isReconnecting" : status === void 0 ? "" : " isOffline";
  }
  window.__ModuleLoader__.load({
    id: clientModuleId,
    factory: (require2) => {
      let module = { exports: {} }, React = require2("react"), inject = [
        "connection",
        "slots",
        "locale",
        "workspaces",
        "sessions",
        "uiWorkspace"
      ];
      function RemoteProgressView(props) {
        let progress = props.progress;
        if (progress === void 0) return null;
        let percent = Math.max(0, Math.min(100, Math.round(progress.percent))), activeTransports = new Set(progress.activeTransports ?? []), detail = progress.transports !== void 0 && activeTransports.size > 0 ? React.createElement(
          "span",
          { className: "dshRemoteProgressRoute" },
          props.t(progress.routeVerb === "using" ? "remoteProgressUsingPrefix" : "remoteProgressTryingPrefix"),
          progress.transports.map((transport, index) => React.createElement(
            React.Fragment,
            { key: `${transport}:${index}` },
            index === 0 ? null : React.createElement("span", { className: "dshRemoteProgressRouteArrow", "aria-hidden": !0 }, " -> "),
            React.createElement("span", {
              className: activeTransports.has(transport) ? "isActive" : void 0
            }, transportDiagnosticLabel(transport))
          ))
        ) : props.t(progress.detail);
        return React.createElement(
          "div",
          {
            className: "dshRemoteProgress",
            role: "status",
            "aria-live": "polite"
          },
          React.createElement(
            "div",
            { className: "dshRemoteProgressHeader" },
            React.createElement("strong", null, props.t(progress.label)),
            React.createElement("span", null, `${percent}%`)
          ),
          React.createElement("p", null, detail),
          React.createElement("div", {
            className: "dshRemoteProgressBar",
            role: "progressbar",
            "aria-valuemin": 0,
            "aria-valuemax": 100,
            "aria-valuenow": percent,
            "aria-label": props.t(progress.label)
          }, React.createElement("span", { style: { transform: `scaleX(${percent / 100})` } }))
        );
      }
      async function runConnectHostProgress(preferredTransports, targetDeviceId, control, connectedStep, setProgress, progressRun, action, readyProgress) {
        let runId = progressRun.current + 1;
        progressRun.current = runId;
        let polling = !0, pollTimer, connectedAt, apply2 = (next) => {
          progressRun.current === runId && setProgress(next);
        }, poll = async () => {
          try {
            let status = await control("status");
            if (!polling || progressRun.current !== runId) return;
            (status.connectionProgress?.phase === "connected" || status.connectedTargetDeviceId === targetDeviceId) && connectedAt === void 0 && (connectedAt = Date.now());
            let next = observedConnectionProgress(status, targetDeviceId, preferredTransports, connectedStep, connectedAt);
            next !== void 0 && apply2(next);
          } catch {
          } finally {
            polling && progressRun.current === runId && (pollTimer = window.setTimeout(() => {
              poll();
            }, 300));
          }
        };
        apply2({ label: "remoteProgressCheckingHost", detail: "remoteProgressCheckingHostDetail", percent: 12 });
        let pending = action();
        poll();
        try {
          let result = await pending;
          return polling = !1, pollTimer !== void 0 && window.clearTimeout(pollTimer), apply2(readyProgress?.(result) ?? { label: "remoteProgressReady", detail: "remoteProgressReadyDetail", percent: 100 }), await new Promise((resolve) => window.setTimeout(resolve, 520)), result;
        } finally {
          polling = !1, pollTimer !== void 0 && window.clearTimeout(pollTimer), progressRun.current === runId && setProgress(void 0);
        }
      }
      function RemotePluginOptions(props) {
        let { t } = props, [open, setOpen] = React.useState(props.view === "page"), [serverUrl, setServerUrl] = React.useState(""), [codexEnabled, setCodexEnabled] = React.useState(!0), [codexBinary, setCodexBinary] = React.useState("codex"), [portsBusy, setPortsBusy] = React.useState(!1), [previewPorts, setPreviewPorts] = React.useState(""), [associations, setAssociations] = React.useState({}), [loaded, setLoaded] = React.useState(!1), [writable, setWritable] = React.useState(!1), [busy, setBusy] = React.useState(!1), [terminalEnabled, setTerminalEnabled] = React.useState(!1), [terminalBusy, setTerminalBusy] = React.useState(!1), [codexBusy, setCodexBusy] = React.useState(!1), [acpBackends, setAcpBackends] = React.useState([]), [acpAvailability, setAcpAvailability] = React.useState({}), [acpChecking, setAcpChecking] = React.useState({}), [acpCheckResults, setAcpCheckResults] = React.useState({}), [addingAcp, setAddingAcp] = React.useState(!1), [acpName, setAcpName] = React.useState(""), [acpCommand, setAcpCommand] = React.useState(""), [acpArguments, setAcpArguments] = React.useState("acp"), [reconnectBusy, setReconnectBusy] = React.useState(!1), [hostStatus, setHostStatus] = React.useState(void 0), [hostName, setHostName] = React.useState(""), [hostDeviceId, setHostDeviceId] = React.useState(""), [notice, setNotice] = React.useState(void 0), [error, setError] = React.useState(void 0), [settingsView, setSettingsView] = React.useState(void 0), persistedServerUrl = settingsView?.config.serverUrl ?? DEFAULT_REMOTE_SERVER_URL, association = associations.client ?? associations.host, serverDirty = settingsView !== void 0 && serverUrl !== persistedServerUrl, draftDirty = serverDirty, applyView = (view) => {
          setSettingsView(view), setServerUrl(view.config.serverUrl ?? DEFAULT_REMOTE_SERVER_URL), setCodexEnabled(view.config.codex?.enabled ?? !1), setCodexBinary(view.config.codex?.binary ?? "codex"), setTerminalEnabled(view.config.terminal?.enabled ?? !0), setPreviewPorts((view.config.loopback?.ports ?? []).join(", ")), setAcpBackends((view.config.acp?.backends ?? []).map((item) => ({ id: item.id, enabled: item.enabled !== !1 }))), setAcpAvailability(view.acpAvailability ?? {}), setAssociations(view.associations ?? (view.association === void 0 ? {} : { host: view.association })), setWritable(view.writable), setLoaded(!0);
        }, load = async () => {
          let [view, status] = await Promise.all([
            props.control("settings.get"),
            props.control("status").catch(() => {
            })
          ]);
          applyView(view), setHostStatus(status?.host), setHostName(status?.deviceName ?? ""), setHostDeviceId(status?.host?.deviceId ?? "");
        };
        React.useEffect(() => {
          load().catch((reason) => setError(messageOf(reason)));
        }, []), React.useEffect(() => {
          if (association !== void 0)
            return props.statusFeed.subscribe((status) => {
              setHostStatus(status.host), setHostName(status.deviceName ?? ""), setHostDeviceId(status.host?.deviceId ?? "");
            });
        }, [association !== void 0]);
        let save = async (event) => {
          if (event?.preventDefault(), !(!writable || !serverDirty)) {
            setBusy(!0), setNotice(void 0), setError(void 0);
            try {
              let view = await props.control("settings.server.set", {
                serverUrl
              });
              applyView(view), setNotice({ key: "serverSaved" });
            } catch (reason) {
              setError(messageOf(reason));
            } finally {
              setBusy(!1);
            }
          }
        }, logout = async () => {
          setBusy(!0), setError(void 0), setNotice(void 0);
          try {
            let view = await props.control("settings.logout");
            applyView(view), setNotice({ key: view.deepseekSignedOut === !0 ? "signedOutDeepseek" : "signedOut" });
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setBusy(!1);
          }
        }, reconnectHost = async () => {
          setReconnectBusy(!0), setError(void 0), setNotice(void 0);
          try {
            let status = await props.control("host.reconnect");
            setHostStatus(status.host), setNotice({ key: "reconnectStarted" });
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setReconnectBusy(!1);
          }
        }, setCurrentDeviceControl = async (enabled) => {
          setBusy(!0), setError(void 0), setNotice(void 0);
          try {
            setHostStatus((await setDeviceControl(props.control, enabled, hostStatus?.authorized === !0)).host);
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setBusy(!1);
          }
        }, setCodexRemote = async (enabled) => {
          let previous = codexEnabled;
          setCodexEnabled(enabled), setCodexBusy(!0), setError(void 0), setNotice(void 0);
          try {
            let view = await props.control("settings.codex.set", { enabled });
            applyView(view), setNotice({ key: "codexSaved" });
          } catch (reason) {
            setCodexEnabled(previous), setError(messageOf(reason));
          } finally {
            setCodexBusy(!1);
          }
        }, setCodexBinaryPath = async (binary) => {
          setCodexBusy(!0), setError(void 0), setNotice(void 0);
          try {
            let view = await props.control("settings.codex.set", { enabled: codexEnabled, binary });
            applyView(view), setNotice({ key: "codexSaved" });
          } catch (reason) {
            setCodexBinary(settingsView?.config.codex?.binary ?? "codex"), setError(messageOf(reason));
          } finally {
            setCodexBusy(!1);
          }
        }, setTerminalRemote = async (enabled) => {
          let previous = terminalEnabled, portsDraft = previewPorts;
          setTerminalEnabled(enabled), setTerminalBusy(!0), setError(void 0), setNotice(void 0);
          try {
            let view = await props.control("settings.development.set", { terminalEnabled: enabled });
            applyView(view), setPreviewPorts(portsDraft), setNotice({ key: "terminalSaved" });
          } catch (reason) {
            setTerminalEnabled(previous), setError(messageOf(reason));
          } finally {
            setTerminalBusy(!1);
          }
        }, savePreviewPorts = async () => {
          setPortsBusy(!0), setError(void 0), setNotice(void 0);
          try {
            let ports = previewPorts.trim() === "" ? [] : previewPorts.split(/[,，]/).map((value) => Number(value.trim())), view = await props.control("settings.development.set", { ports });
            applyView(view), setNotice({ key: "developmentSaved" });
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setPortsBusy(!1);
          }
        }, checkAcp = async (backend) => {
          setAcpChecking((current) => ({ ...current, [backend]: !0 })), setAcpCheckResults((current) => ({ ...current, [backend]: void 0 })), setError(void 0);
          try {
            let view = await props.control("settings.get");
            applyView(view);
            let available = view.acpAvailability?.[backend] === !0;
            if (!available) {
              let disabledView = await props.control("settings.acp.set", { backend, enabled: !1 });
              applyView(disabledView);
            }
            setAcpCheckResults((current) => ({ ...current, [backend]: available })), setNotice({ key: available ? "acpAvailable" : "acpUnavailable" }), window.setTimeout(() => setAcpCheckResults((current) => ({ ...current, [backend]: void 0 })), 5e3);
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setAcpChecking((current) => ({ ...current, [backend]: !1 }));
          }
        }, addAcp = async () => {
          setBusy(!0), setError(void 0);
          try {
            let view = await props.control("settings.acp.add", {
              id: acpName.trim(),
              command: acpCommand.trim(),
              args: acpArguments.trim() === "" ? [] : acpArguments.trim().split(/\s+/)
            });
            applyView(view), setAddingAcp(!1), setAcpName(""), setAcpCommand(""), setAcpArguments("acp");
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setBusy(!1);
          }
        }, removeAcp = async (backend) => {
          setBusy(!0), setError(void 0);
          try {
            let view = await props.control("settings.acp.remove", { id: backend });
            applyView(view);
          } catch (reason) {
            setError(messageOf(reason));
          } finally {
            setBusy(!1);
          }
        }, discard = () => {
          settingsView !== void 0 && applyView(settingsView), setNotice(void 0), setError(void 0);
        }, developmentSetting = React.createElement(
          "section",
          { className: "dshRemoteDevelopmentSettings" },
          React.createElement(
            "div",
            { className: "dshRemoteAuthorizationSetting" },
            React.createElement(
              "div",
              null,
              React.createElement("strong", null, t("terminalRemote")),
              React.createElement("p", null, t("terminalRemoteHint"))
            ),
            React.createElement("input", {
              type: "checkbox",
              role: "switch",
              checked: terminalEnabled,
              disabled: busy || terminalBusy || !writable,
              "aria-label": t("terminalRemote"),
              onChange: (event) => void setTerminalRemote(event.target.checked)
            })
          ),
          React.createElement(
            "div",
            { className: "dshRemotePathField" },
            React.createElement("span", null, t("previewPorts")),
            React.createElement(
              "div",
              { className: "dshRemotePortsRow" },
              React.createElement("input", {
                value: previewPorts,
                maxLength: 128,
                disabled: !writable,
                placeholder: "3000, 5173",
                "aria-label": t("previewPorts"),
                onChange: (event) => setPreviewPorts(event.target.value)
              }),
              React.createElement("button", {
                type: "button",
                disabled: portsBusy || terminalBusy || !writable,
                onClick: () => void savePreviewPorts()
              }, t("developmentSave"))
            ),
            React.createElement("small", null, t("previewPortsHint"))
          )
        ), codexBinaryConfigured = settingsView?.config.codex?.binary ?? "codex", codexBinaryDiscovered = settingsView?.discoveredCodexBinary, codexBinaryPinned = codexBinaryConfigured !== "codex", codexBinaryEditable = codexBinaryPinned || codexBinaryDiscovered === void 0, codexDomainStatus = settingsView?.codexStatus, codexUnavailable = codexDomainStatus !== void 0 && codexDomainStatus.available === !1, codexSetting = React.createElement(CodexConnectionFields, {
          t,
          enabled: codexEnabled,
          onEnabledChange: (next) => void setCodexRemote(next),
          busy: busy || codexBusy,
          writable,
          unavailable: codexUnavailable,
          state: codexDomainStatus?.state,
          reasonCode: codexDomainStatus?.error,
          editable: codexBinaryEditable,
          pinned: codexBinaryPinned,
          discovered: codexBinaryDiscovered,
          value: codexBinary,
          onValueChange: (next) => setCodexBinary(next),
          onConfirm: () => void setCodexBinaryPath(codexBinary),
          onUseAuto: () => {
            setCodexBinary(""), setCodexBinaryPath("");
          }
        }), acpSetting = React.createElement(
          "details",
          { className: "dshRemoteAuthorizationSetting dshRemoteAcpSetting" },
          React.createElement(
            "summary",
            null,
            React.createElement(
              "div",
              { className: "dshRemoteAcpSummaryText" },
              React.createElement("strong", null, "ACP Agent backends"),
              React.createElement("p", null, t("acpHint"))
            )
          ),
          React.createElement(
            "div",
            { className: "dshRemoteAcpList" },
            acpBackends.map((item) => React.createElement(
              "div",
              { key: item.id, className: "dshRemoteAuthorizationSetting" },
              React.createElement("span", null, item.id),
              React.createElement(
                "span",
                { className: "dshRemoteAcpCheckCell" },
                React.createElement(
                  "a",
                  { href: `#check-acp-${item.id}`, className: `dshRemoteAcpCheckLink${acpChecking[item.id] ? " isChecking" : acpCheckResults[item.id] === void 0 ? "" : acpCheckResults[item.id] ? " isPassed" : " isFailed"}`, onClick: (event) => {
                    event.preventDefault(), checkAcp(item.id);
                  } },
                  acpChecking[item.id] ? t("checkingAcp") : acpCheckResults[item.id] === void 0 ? t("checkAcp") : t(acpCheckResults[item.id] ? "acpCheckPassed" : "acpCheckFailed")
                )
              ),
              ["codex", "cursor", "kimi"].includes(item.id) ? null : React.createElement("a", { href: `#remove-acp-${item.id}`, className: "dshRemoteAcpRemoveLink", onClick: (event) => {
                event.preventDefault(), removeAcp(item.id);
              } }, t("removeAcp")),
              React.createElement("input", {
                type: "checkbox",
                role: "switch",
                checked: item.enabled && acpAvailability[item.id] === !0,
                disabled: busy || !writable || !acpAvailability[item.id],
                onChange: (event) => void props.control("settings.acp.set", { backend: item.id, enabled: event.target.checked }).then((view) => {
                  applyView(view), setAcpBackends((view.config.acp?.backends ?? []).map((v) => ({ id: v.id, enabled: v.enabled !== !1 })));
                }).catch((reason) => setError(messageOf(reason)))
              })
            )),
            addingAcp ? React.createElement(
              "div",
              { className: "dshRemoteAcpAddForm" },
              React.createElement("input", { value: acpName, placeholder: t("acpName"), "aria-label": t("acpName"), onChange: (event) => setAcpName(event.target.value) }),
              React.createElement("input", { value: acpCommand, placeholder: t("acpCommand"), "aria-label": t("acpCommand"), onChange: (event) => setAcpCommand(event.target.value) }),
              React.createElement("input", { value: acpArguments, placeholder: t("acpArguments"), "aria-label": t("acpArguments"), onChange: (event) => setAcpArguments(event.target.value) }),
              React.createElement("button", { type: "button", disabled: busy || acpName.trim() === "" || acpCommand.trim() === "", onClick: () => void addAcp() }, t("addAcp"))
            ) : React.createElement("a", { href: "#add-acp", className: "dshRemoteAcpAddLink", onClick: (event) => {
              event.preventDefault(), setAddingAcp(!0);
            } }, t("addAcp"))
          )
        );
        return props.view === "summary" ? React.createElement("span", null, t("pluginDescription")) : React.createElement(
          props.view === "page" ? "div" : "li",
          { className: `dshRemotePluginCard${open ? " isOpen" : ""}${props.view === "page" ? " dshRemotePluginModern" : ""}` },
          React.createElement(
            "div",
            { className: "dshRemotePluginCardHeader" },
            React.createElement(
              "button",
              {
                type: "button",
                className: "dshRemotePluginCardToggle",
                "aria-expanded": open,
                "aria-label": t(open ? "collapseSettings" : "expandSettings", { name: t("pluginTitle") }),
                onClick: () => setOpen((current) => !current)
              },
              React.createElement(
                "span",
                { className: "dshRemotePluginCardHeading" },
                React.createElement("strong", null, t("pluginTitle")),
                React.createElement("span", null, t("pluginDescription"))
              ),
              draftDirty ? React.createElement("span", { className: "dshRemotePluginCardStatus" }, t("unsaved")) : association === void 0 ? null : React.createElement("span", {
                className: `dshRemotePluginCardStatus${connectionStatusClass(hostStatus)}`
              }, hostStatus === void 0 ? t("associated") : connectionStatusLabel(hostStatus, t)),
              React.createElement("span", { className: "dshRemotePluginCardChevron", "aria-hidden": !0 }, "\u2304")
            )
          ),
          open ? React.createElement(
            "div",
            { className: "dshRemotePluginCardBody" },
            loaded ? association !== void 0 ? React.createElement(
              "div",
              { className: "dshRemoteSettings" },
              React.createElement(
                "div",
                { className: "dshRemoteSettingsTop" },
                React.createElement(
                  "div",
                  { className: "dshRemoteAssociation" },
                  React.createElement("span", null, t(association.account === void 0 ? "authorization" : "account")),
                  React.createElement("strong", null, association.account ?? t("authorizationComplete")),
                  React.createElement("p", null, association.account === void 0 ? serverUrl : t("authorizedOn", { role: "Remote", serverUrl }))
                )
              ),
              React.createElement(
                "div",
                { className: "dshRemoteHostIdentity" },
                React.createElement(
                  "div",
                  null,
                  React.createElement("span", null, t("hostName")),
                  React.createElement("strong", null, hostName || "\u2014")
                ),
                React.createElement(
                  "div",
                  null,
                  React.createElement("span", null, t("deviceId")),
                  React.createElement("code", null, hostDeviceId || "\u2014")
                )
              ),
              React.createElement(
                "div",
                { className: "dshRemoteField" },
                React.createElement("label", { htmlFor: "dsh-remote-server-url-authorized" }, t("serverUrl")),
                React.createElement("input", {
                  id: "dsh-remote-server-url-authorized",
                  type: "url",
                  value: serverUrl,
                  disabled: !0,
                  required: !0,
                  placeholder: DEFAULT_REMOTE_SERVER_URL,
                  onChange: (event) => {
                    setServerUrl(event.target.value), setNotice(void 0);
                  }
                }),
                React.createElement("p", null, t("serverUrlHint"))
              ),
              developmentSetting,
              codexSetting,
              acpSetting,
              React.createElement(
                "div",
                { className: "dshRemoteAuthorizationSetting" },
                React.createElement(
                  "div",
                  null,
                  React.createElement("strong", null, t("allowControlCurrentDevice")),
                  React.createElement("p", null, t("thisMachineHost"))
                ),
                React.createElement("input", {
                  type: "checkbox",
                  role: "switch",
                  disabled: busy,
                  "aria-label": t("allowControlCurrentDevice"),
                  checked: hostStatus?.authorized === !0 && hostStatus?.paused !== !0,
                  onChange: (event) => void setCurrentDeviceControl(event.target.checked)
                })
              ),
              React.createElement(
                "div",
                { className: "dshRemoteConnection", "aria-live": "polite" },
                React.createElement(
                  "div",
                  { className: "dshRemoteConnectionSummary" },
                  React.createElement("span", null, t("connection")),
                  React.createElement(
                    "strong",
                    null,
                    React.createElement("span", {
                      className: `dshRemoteConnectionDot${connectionStatusClass(hostStatus)}`,
                      "aria-hidden": !0
                    }),
                    connectionStatusLabel(hostStatus, t)
                  ),
                  React.createElement("p", null, hostStatus === void 0 ? t("checkingConnection") : hostStatus.lastActiveAt === void 0 ? t("neverConnected") : t("lastActive", { time: formatLocalTime(hostStatus.lastActiveAt) }))
                ),
                React.createElement("button", {
                  type: "button",
                  className: "dshRemoteReconnect",
                  // A paused Host is not a broken one: there is nothing to reconnect, and the switch is the
                  // only control that may turn reachability back on.
                  disabled: reconnectBusy || hostStatus?.configured === !1 || hostStatus?.paused === !0,
                  onClick: () => void reconnectHost()
                }, t(reconnectBusy ? "reconnectingAction" : "reconnect"))
              ),
              hostStatus?.error === void 0 || hostStatus.online ? null : React.createElement("p", { className: "dshRemoteConnectionIssue", role: "status" }, connectionErrorMessage(hostStatus.error, t)),
              writable ? null : React.createElement("p", { className: "dshRemoteError" }, t("readOnly")),
              React.createElement(
                "div",
                { className: "dshRemoteSettingsFooter" },
                React.createElement("p", {
                  className: error !== void 0 ? "dshRemoteError" : "dshRemoteNotice",
                  role: error !== void 0 ? "alert" : "status"
                }, error ?? (notice === void 0 ? "" : t(notice.key, notice.params))),
                draftDirty ? React.createElement(
                  React.Fragment,
                  null,
                  React.createElement("button", { type: "button", className: "dshRemoteDiscard", disabled: busy, onClick: discard }, t("discard")),
                  React.createElement("button", { type: "button", className: "dshRemoteSave", disabled: busy || !writable, onClick: () => void save() }, t(busy ? "saving" : "save")),
                  React.createElement("button", { type: "button", className: "dshRemoteDiscard", disabled: busy || !writable, onClick: () => void logout() }, t(busy ? "signingOut" : "signOut"))
                ) : React.createElement("button", {
                  type: "button",
                  className: "dshRemoteDiscard",
                  disabled: busy || !writable,
                  onClick: () => void logout()
                }, t(busy ? "signingOut" : "signOut"))
              )
            ) : React.createElement(
              "form",
              { className: "dshRemoteSettings", noValidate: !0, onSubmit: (event) => void save(event) },
              React.createElement(
                "div",
                { className: "dshRemoteHostIdentity" },
                React.createElement(
                  "div",
                  null,
                  React.createElement("span", null, t("hostName")),
                  React.createElement("strong", null, hostName || "\u2014")
                ),
                React.createElement(
                  "div",
                  null,
                  React.createElement("span", null, t("deviceId")),
                  React.createElement("code", null, hostDeviceId || "\u2014")
                )
              ),
              React.createElement(
                "div",
                { className: "dshRemoteField" },
                React.createElement("label", { htmlFor: "dsh-remote-server-url" }, t("serverUrl")),
                React.createElement("input", {
                  id: "dsh-remote-server-url",
                  type: "url",
                  value: serverUrl,
                  disabled: busy || !writable,
                  required: !0,
                  placeholder: DEFAULT_REMOTE_SERVER_URL,
                  onChange: (event) => {
                    setServerUrl(event.target.value), setNotice(void 0);
                  }
                }),
                React.createElement("p", null, t("serverUrlHint"))
              ),
              developmentSetting,
              codexSetting,
              acpSetting,
              React.createElement("p", { className: "dshRemoteSettingsState" }, t("authorizeFromRemote")),
              writable ? null : React.createElement("p", { className: "dshRemoteError" }, t("readOnly")),
              React.createElement(
                "div",
                { className: "dshRemoteSettingsFooter" },
                React.createElement("p", {
                  className: error !== void 0 ? "dshRemoteError" : "dshRemoteNotice",
                  role: error !== void 0 ? "alert" : "status"
                }, error ?? (notice === void 0 ? "" : t(notice.key, notice.params))),
                React.createElement("button", { type: "button", className: "dshRemoteDiscard", disabled: busy || !draftDirty, onClick: discard }, t("discard")),
                React.createElement("button", { type: "submit", className: "dshRemoteSave", disabled: busy || !writable || !serverDirty }, t(busy ? "saving" : "save"))
              )
            ) : React.createElement("p", { className: "dshRemoteSettingsState" }, error ?? t("loadingSettings"))
          ) : null
        );
      }
      function RemoteWorkspaceAction(props) {
        let { t } = props, [open, setOpen] = React.useState(!1);
        React.useEffect(() => {
          open && props.control("settings.get").catch(() => {
          });
        }, [open]);
        let [status, setStatus] = React.useState(void 0), [devices, setDevices] = React.useState([]), [selectedHost, setSelectedHost] = React.useState(void 0), [connectingHost, setConnectingHost] = React.useState(void 0), [workspaces, setWorkspaces] = React.useState([]), [codexWorkspaces, setCodexWorkspaces] = React.useState([]), [codexNotice, setCodexNotice] = React.useState(void 0), [codexAvailable, setCodexAvailable] = React.useState(!1), codexProbeAvailable = React.useRef(!1), [codexOpen, setCodexOpen] = React.useState(!1), [workspaceBackend, setWorkspaceBackend] = React.useState("harness"), [codexWorkspaceId, setCodexWorkspaceId] = React.useState(void 0), [directory, setDirectory] = React.useState(void 0), [path, setPath] = React.useState(""), [addingWorkspace, setAddingWorkspace] = React.useState(!1), [showAllWorkspaces, setShowAllWorkspaces] = React.useState(!1), [showAllCodexWorkspaces, setShowAllCodexWorkspaces] = React.useState(!1), [devicesOpen, setDevicesOpen] = React.useState(!1), workspaceListId = "dsh-remote-workspace-list", codexWorkspaceHeadingId = "dsh-remote-codex-workspace-heading", codexWorkspaceListId = "dsh-remote-codex-workspace-list", [busy, setBusy] = React.useState(!1), [needsAuthorization, setNeedsAuthorization] = React.useState(!1), authorizedOnce = React.useRef(!1), [authorizationResolved, setAuthorizationResolved] = React.useState(!1), [loginServerUrl, setLoginServerUrl] = React.useState(DEFAULT_REMOTE_SERVER_URL), [editingServerUrl, setEditingServerUrl] = React.useState(!1), [awaitingDeepSeek, setAwaitingDeepSeek] = React.useState(!1), [pendingAuthorizeUrl, setPendingAuthorizeUrl] = React.useState(void 0), [loginMethod] = React.useState(
          isEnabledQrProvider(props.preferredQrProvider) ? props.preferredQrProvider : defaultQrProvider
        ), [qrSession, setQrSession] = React.useState(void 0), [qrExpired, setQrExpired] = React.useState(!1), [progress, setProgress] = React.useState(void 0), progressRun = React.useRef(0), qrFlowRun = React.useRef(0), [notice, setNotice] = React.useState(void 0), [confirmingSignOut, setConfirmingSignOut] = React.useState(!1), [confirmingRevoke, setConfirmingRevoke] = React.useState(!1), [error, setError] = React.useState(void 0);
        React.useEffect(() => {
          open || setDevicesOpen(!1);
        }, [open]), React.useEffect(() => {
          if (!open) return;
          let closeOnEscape = (event) => {
            if (event.key === "Escape") {
              if (devicesOpen) {
                event.stopPropagation(), setDevicesOpen(!1);
                return;
              }
              setOpen(!1);
            }
          };
          return window.addEventListener("keydown", closeOnEscape), () => window.removeEventListener("keydown", closeOnEscape);
        }, [open, devicesOpen]), React.useEffect(() => {
          if (!devicesOpen) return;
          let closeOnPointer = (event) => {
            let target = event.target;
            target instanceof Element && target.closest(".dshRemoteConnectedMenu") === null && setDevicesOpen(!1);
          };
          return window.addEventListener("mousedown", closeOnPointer), () => window.removeEventListener("mousedown", closeOnPointer);
        }, [devicesOpen]), React.useEffect(() => {
          (status?.host?.connectedClients?.length ?? 0) === 0 && setDevicesOpen(!1);
        }, [status?.host?.connectedClients?.length]), React.useEffect(() => {
          props.control("status").then(setStatus).catch(() => {
          });
        }, []), React.useEffect(() => {
          let verify = () => {
            document.visibilityState === "visible" && props.control("client.connection.verify").then(setStatus).catch(() => {
            });
          };
          return document.addEventListener("visibilitychange", verify), window.addEventListener("focus", verify), () => {
            document.removeEventListener("visibilitychange", verify), window.removeEventListener("focus", verify);
          };
        }, []), React.useEffect(() => {
          status?.serverUrl !== void 0 && loginServerUrl === DEFAULT_REMOTE_SERVER_URL && setLoginServerUrl(status.serverUrl);
        }, [status?.serverUrl]), React.useEffect(() => {
          let remoteActive = status?.mode === "remote";
          return document.documentElement.classList.toggle("dshRemoteTargetActive", remoteActive), () => {
            remoteActive && document.documentElement.classList.remove("dshRemoteTargetActive");
          };
        }, [status?.mode]);
        let startQrLogin = async (provider) => {
          let run = ++qrFlowRun.current;
          setBusy(!0), setError(void 0), setQrExpired(!1);
          try {
            let session = await props.control("client.account.qr.start", { provider });
            if (run !== qrFlowRun.current) return;
            setQrSession(session);
          } catch (reason) {
            run === qrFlowRun.current && setError(messageOf(reason));
          } finally {
            run === qrFlowRun.current && setBusy(!1);
          }
        };
        React.useEffect(() => {
          !QR_LOGIN_ENABLED || !open || !needsAuthorization || loginMethod === "password" || qrSession !== void 0 || qrExpired || startQrLogin(loginMethod);
        }, [open, needsAuthorization, loginMethod, qrSession, qrExpired]), React.useEffect(() => {
          if (!QR_LOGIN_ENABLED || !open || loginMethod === "password" || qrSession === void 0) return;
          let active = !0, polling = !1, settled = !1, run = qrFlowRun.current, timer, poll = () => {
            polling || settled || (polling = !0, props.control("client.account.qr.poll", { qrId: qrSession.qrId }).then(async (result) => {
              if (!(!active || settled || run !== qrFlowRun.current))
                if (result.status === "complete") {
                  settled = !0, timer !== void 0 && window.clearInterval(timer), setBusy(!0), setError(void 0), setQrExpired(!1), authorizedOnce.current = !0, setNeedsAuthorization(!1);
                  try {
                    let [nextDevices, nextStatus] = await Promise.all([
                      props.control("devices"),
                      props.control("status")
                    ]);
                    active && run === qrFlowRun.current && (setDevices(nextDevices), setStatus(nextStatus));
                  } catch (reason) {
                    active && run === qrFlowRun.current && setError(messageOf(reason));
                  } finally {
                    run === qrFlowRun.current && (setQrSession(void 0), setBusy(!1));
                  }
                } else result.status === "expired" && (settled = !0, timer !== void 0 && window.clearInterval(timer), setQrExpired(!0), setQrSession(void 0));
            }).catch((reason) => {
              active && !settled && run === qrFlowRun.current && setError(messageOf(reason));
            }).finally(() => {
              polling = !1;
            }));
          };
          return poll(), timer = window.setInterval(poll, 1500), () => {
            active = !1, timer !== void 0 && window.clearInterval(timer);
          };
        }, [open, loginMethod, qrSession]);
        let selectHost = async (host) => {
          setBusy(!0), setError(void 0), setConnectingHost(host), setCodexWorkspaces([]), setCodexNotice(void 0), setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1);
          try {
            let result = await runConnectHostProgress(
              status?.preferredTransports,
              host.deviceId,
              props.control,
              { label: "remoteProgressLoadingWorkspaces", detail: "remoteProgressLoadingWorkspacesDetail" },
              setProgress,
              progressRun,
              async () => {
                let nextWorkspaces = await props.control("workspaces.list", {
                  targetDeviceId: host.deviceId
                }), codexReason, nextCodexWorkspaces = await props.control("codex.workspaces.list", {
                  targetDeviceId: host.deviceId
                }).catch(async (reason) => {
                  let code = typeof reason?.code == "string" ? String(reason.code) : "";
                  return ["TRANSPORT_CLOSED", "NOT_CONNECTED", "CONNECTION_FAILED", "RPC_TIMEOUT"].includes(code) ? await props.control("codex.workspaces.list", {
                    targetDeviceId: host.deviceId
                  }).catch((again) => (codexReason = remoteErrorMessage(again, t), [])) : (codexReason = remoteErrorMessage(reason, t), []);
                }), nextStatus = await props.control("status").catch(() => {
                });
                return nextStatus !== void 0 && setStatus(nextStatus), setCodexNotice(codexReason), { workspaces: nextWorkspaces, codexWorkspaces: nextCodexWorkspaces, status: nextStatus };
              },
              (result2) => connectedProgress(result2.status)
            );
            setWorkspaces(result.workspaces), setCodexWorkspaces(result.codexWorkspaces), setCodexAvailable(result.status?.remoteFeatures?.codex === !0), codexProbeAvailable.current = result.status?.remoteFeatures?.codex === !0, setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setSelectedHost(host), setPath(""), setAddingWorkspace(!1), setDirectory(void 0);
          } catch (reason) {
            setError(remoteErrorMessage(reason, t));
          } finally {
            setConnectingHost(void 0), setBusy(!1);
          }
        };
        async function loadHarnessWorkspaces(host) {
          try {
            setWorkspaces(await props.control("workspaces.list", { targetDeviceId: host.deviceId }));
          } catch (reason) {
            setError(remoteErrorMessage(reason, t));
          }
        }
        async function loadCodexWorkspaces(host) {
          try {
            setCodexWorkspaces(await props.control("codex.workspaces.list", {
              targetDeviceId: host.deviceId
            })), setCodexNotice(void 0), setCodexAvailable(!0);
          } catch (reason) {
            setCodexWorkspaces([]), setCodexNotice(remoteErrorMessage(reason, t)), setCodexAvailable(!1);
          }
        }
        let browseDirectory = async (nextPath) => {
          if (selectedHost !== void 0) {
            setBusy(!0), setError(void 0);
            try {
              let listing = await props.control("directory.list", {
                targetDeviceId: selectedHost.deviceId,
                ...nextPath === void 0 ? {} : { path: nextPath }
              });
              setDirectory(listing), setCodexWorkspaceId(void 0), setPath(listing.path);
            } catch (reason) {
              setError(remoteErrorMessage(reason, t));
            } finally {
              setBusy(!1);
            }
          }
        }, startAddingWorkspace = (backend) => {
          setAddingWorkspace(!0), setWorkspaceBackend(backend), setCodexWorkspaceId(void 0), setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1), setDirectory(void 0), setPath(""), browseDirectory();
        }, cancelAddingWorkspace = () => {
          setAddingWorkspace(!1), setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setDirectory(void 0), setPath("");
        }, refreshRemote = async () => {
          setBusy(!0), setNotice(void 0), setError(void 0), props.control("settings.get").then(async (view) => {
            view.config.codex?.enabled !== !1 && view.codexStatus?.available !== !0 && await props.control("settings.codex.set", { enabled: !0 });
          }).catch(() => {
          });
          try {
            let nextStatus = await props.control("status");
            if (setStatus(nextStatus), !nextStatus.available) {
              setDevices([]), authorizedOnce.current = !0, setNeedsAuthorization(!1), setAuthorizationResolved(!0), setSelectedHost(void 0), setWorkspaces([]), setCodexWorkspaces([]), setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1), setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setPath(""), setAddingWorkspace(!1), setDirectory(void 0);
              return;
            }
            let storedLocally;
            try {
              storedLocally = (await props.control("authorization.local"))?.stored === !0, setNeedsAuthorization(!storedLocally);
            } catch {
              authorizedOnce.current = !0, setNeedsAuthorization(!1);
            } finally {
              setAuthorizationResolved(!0);
            }
            try {
              let nextDevices = await props.control("devices");
              if (setDevices(nextDevices), !authorizedOnce.current && storedLocally !== void 0 && setNeedsAuthorization(authorizationStillNeeded(storedLocally, nextDevices.length)), selectedHost !== void 0) {
                let nextSelectedHost = nextDevices.find((device) => device.deviceId === selectedHost.deviceId);
                nextSelectedHost === void 0 ? (setSelectedHost(void 0), setWorkspaces([]), setCodexWorkspaces([]), setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1), setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setPath(""), setAddingWorkspace(!1), setDirectory(void 0)) : setSelectedHost(nextSelectedHost);
              }
            } catch {
              setDevices([]), authorizedOnce.current = !1, setNeedsAuthorization(!0), setSelectedHost(void 0), setWorkspaces([]), setCodexWorkspaces([]), setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1), setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setPath(""), setAddingWorkspace(!1), setDirectory(void 0);
            }
            selectedHost !== void 0 && (await props.control("remote.session.recover").then((next) => setStatus(next)).catch(() => {
            }), await Promise.all([loadHarnessWorkspaces(selectedHost), loadCodexWorkspaces(selectedHost)]));
          } catch (reason) {
            setError(remoteErrorMessage(reason, t)), setAuthorizationResolved(!0);
          } finally {
            setBusy(!1);
          }
        }, show = async () => {
          setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1), setOpen(!0), await refreshRemote();
          for (let attempt = 0; attempt < 10; attempt++) {
            let list = await props.control("devices").catch(() => {
            });
            if (list !== void 0) {
              setDevices(list), authorizedOnce.current = !0, setNeedsAuthorization(!1), setAuthorizationResolved(!0), await refreshRemote();
              return;
            }
            await new Promise((resolve) => {
              setTimeout(resolve, 1e3);
            });
          }
        };
        React.useEffect(() => {
          if (!(!open || selectedHost !== void 0))
            return props.statusFeed.subscribe(setStatus);
        }, [open, selectedHost]), React.useEffect(() => {
          if (!open || selectedHost === void 0) return;
          let cancelled = !1, probe = async () => {
            let result = await props.control("remote.features.probe").catch(() => {
            });
            if (cancelled || result === void 0 || result.targetDeviceId !== void 0 && result.targetDeviceId !== selectedHost.deviceId) return;
            let nextStatus = await props.control("status").catch(() => {
            });
            if (cancelled) return;
            nextStatus !== void 0 && setStatus(nextStatus);
            let available = result.connected === !0 && result.features?.codex === !0;
            available !== codexProbeAvailable.current && await loadCodexWorkspaces(selectedHost), codexProbeAvailable.current = available;
          };
          probe();
          let timer = window.setInterval(() => void probe(), 5e3);
          return () => {
            cancelled = !0, window.clearInterval(timer);
          };
        }, [open, selectedHost]);
        let chooseAnotherHost = () => {
          setSelectedHost(void 0), setWorkspaces([]), setCodexWorkspaces([]), setCodexNotice(void 0), setCodexAvailable(!1), codexProbeAvailable.current = !1, setShowAllWorkspaces(!1), setShowAllCodexWorkspaces(!1), setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setDirectory(void 0), setPath(""), setAddingWorkspace(!1), setError(void 0);
        }, signingIn = busy || status?.authorizing === !0 || needsAuthorization && status?.hasDeepSeekSession === !0;
        React.useEffect(() => {
          if (!signingIn) return;
          let tries = 0, timer = window.setInterval(() => {
            tries += 1, (async () => {
              let next = await props.control("status").catch(() => {
              });
              next !== void 0 && setStatus(next);
              let list = await props.control("devices").catch(() => {
              });
              list !== void 0 && (setDevices(list), authorizedOnce.current = !0, setNeedsAuthorization(!1), setAuthorizationResolved(!0));
            })(), tries >= 5 && window.clearInterval(timer);
          }, 1e3);
          return () => window.clearInterval(timer);
        }, [signingIn]);
        let requestDeepSeekSignIn = async () => {
          let transport = globalThis.__DSH_TRANSPORT__, callbackOrigin = transport?.streamBaseUrl !== void 0 ? new URL(transport.streamBaseUrl).origin : window.location.origin, result = await props.control("settings.configure", {
            // No `role`: a device has one identity, and the control writes only the Server address plus this
            // sign-in. Sending a role here was the last thing that made one look like a runtime switch.
            serverUrl: loginServerUrl.trim(),
            provider: "deepseek",
            callbackOrigin,
            loginSource: "dshDesktop" in globalThis ? "desktop" : "web",
            locale: navigator.language,
            timezoneOffsetSeconds: -(/* @__PURE__ */ new Date()).getTimezoneOffset() * 60
          });
          return result?.status === "deepseek-sign-in-required" ? { pending: !0, ...typeof result.authorizeUrl == "string" ? { authorizeUrl: result.authorizeUrl } : {} } : { pending: !1 };
        }, signInClient = async () => {
          if (loginServerUrl.trim() === "") return;
          let tab = window.open("", "_blank");
          if (tab !== null)
            try {
              tab.opener = null;
            } catch {
            }
          setBusy(!0), setError(void 0), setAwaitingDeepSeek(!0);
          try {
            let state = await requestDeepSeekSignIn();
            if (state.pending) {
              for (let wait = 0; wait < 30 && state.pending && state.authorizeUrl === void 0; wait++)
                await new Promise((resolve) => {
                  setTimeout(resolve, 1e3);
                }), state = await requestDeepSeekSignIn();
              if (state.authorizeUrl === void 0)
                throw tab?.close(), new Error(t("deepseekAuthorizeUnavailable"));
              setPendingAuthorizeUrl(state.authorizeUrl), tab === null || tab.closed ? window.open(state.authorizeUrl, "_blank", "noopener,noreferrer") : tab.location.replace(state.authorizeUrl);
              for (let poll = 0; poll < 40 && state.pending; poll++)
                await new Promise((resolve) => {
                  setTimeout(resolve, 3e3);
                }), state = await requestDeepSeekSignIn();
              if (state.pending) throw new Error(t("deepseekSignInTimeout"));
            } else
              tab?.close();
            setDevices(await props.control("devices")), setStatus(await props.control("status")), authorizedOnce.current = !0, setNeedsAuthorization(!1), setAuthorizationResolved(!0), setNotice(void 0);
          } catch (reason) {
            setError(remoteErrorMessage(reason, t));
          } finally {
            setAwaitingDeepSeek(!1), setPendingAuthorizeUrl(void 0), setBusy(!1);
          }
        }, openLocalWorkspaces = async () => {
          setBusy(!0), setError(void 0);
          try {
            await props.control("mode.set", { mode: "local" }), window.location.reload();
          } catch (reason) {
            setError(remoteErrorMessage(reason, t)), setBusy(!1);
          }
        }, setCurrentDeviceControl = async (enabled) => {
          setBusy(!0), setError(void 0);
          try {
            setStatus(await setDeviceControl(props.control, enabled, status?.host?.authorized === !0));
          } catch (reason) {
            setError(remoteErrorMessage(reason, t));
          } finally {
            setBusy(!1);
          }
        }, revokeRemote = async () => {
          setBusy(!0), setError(void 0), setNotice(void 0);
          try {
            await props.control("client.device.revoke"), setDevices([]), authorizedOnce.current = !1, setNeedsAuthorization(!0), setStatus(await props.control("status"));
          } catch (reason) {
            setError(remoteErrorMessage(reason, t));
          } finally {
            setConfirmingRevoke(!1), setBusy(!1);
          }
        }, logoutRemote = async () => {
          setBusy(!0), setError(void 0), setNotice(void 0);
          try {
            await props.control("settings.logout"), setDevices([]), authorizedOnce.current = !1, setNeedsAuthorization(!0), setQrSession(void 0), setQrExpired(!1), setStatus(await props.control("status"));
          } catch (reason) {
            setError(remoteErrorMessage(reason, t));
          } finally {
            setConfirmingSignOut(!1), setBusy(!1);
          }
        }, openWorkspace = async (selection) => {
          let targetBackend = selection?.backend ?? workspaceBackend, targetPath = (selection?.path ?? path).trim(), targetCodexWorkspaceId = selection?.backend === "codex" ? selection.workspaceId : selection === void 0 ? codexWorkspaceId : void 0, createWorkspace = selection === void 0 && addingWorkspace;
          if (!(selectedHost === void 0 || targetPath === "" || !createWorkspace && targetBackend === "codex" && targetCodexWorkspaceId === void 0)) {
            setBusy(!0), setError(void 0);
            try {
              let nextStatus = await (targetBackend === "codex" ? createWorkspace ? props.control("codex.workspace.create", {
                targetDeviceId: selectedHost.deviceId,
                path: targetPath
              }) : props.control("codex.workspace.open", {
                targetDeviceId: selectedHost.deviceId,
                workspaceId: targetCodexWorkspaceId
              }) : props.control("workspace.open", {
                targetDeviceId: selectedHost.deviceId,
                path: targetPath
              }));
              setStatus(nextStatus), nextStatus.workspaceSelection !== void 0 && window.sessionStorage.setItem(pendingWorkspaceSelectionKey, JSON.stringify(nextStatus.workspaceSelection)), window.location.reload();
            } catch (reason) {
              setError(remoteErrorMessage(reason, t)), setBusy(!1);
            }
          }
        }, remoteLabel = status?.mode === "remote" ? t("activeRemote", { name: status.target?.name ?? t("host") }) : t("remoteEntry"), visibleWorkspaces = showAllWorkspaces ? workspaces : workspaces.slice(0, 3), visibleCodexWorkspaces = showAllCodexWorkspaces ? codexWorkspaces : codexWorkspaces.slice(0, 3), selectedHostDetails = selectedHost === void 0 ? void 0 : [
          formatPlatform(selectedHost.platform),
          selectedHost.harnessVersion === void 0 ? void 0 : t("harnessVersion", { version: selectedHost.harnessVersion }),
          selectedHost.clientVersion === void 0 ? void 0 : t("pluginVersion", { version: selectedHost.clientVersion })
        ].filter(Boolean).join(" \xB7 "), connectedClients = status?.host?.connectedClients ?? [], connectedCount = connectedClients.length, connectedCountLabel = t("connectedClientCount", { count: connectedCount }), connectedMenu = !needsAuthorization && status?.hostAuthorizationAvailable === !0 ? React.createElement(
          "div",
          {
            className: `dshRemoteConnectedMenu${devicesOpen ? " isOpen" : ""}`
          },
          React.createElement("a", {
            href: connectedCount > 0 ? "#connected-devices" : void 0,
            className: `dshRemoteConnectedCount${connectedCount > 0 ? " isOnline isInteractive" : ""}`,
            "aria-expanded": connectedCount > 0 ? devicesOpen : void 0,
            "aria-haspopup": connectedCount > 0 ? "dialog" : void 0,
            title: connectedCountLabel,
            "aria-label": connectedCountLabel,
            onClick: (event) => {
              event.preventDefault(), connectedCount !== 0 && setDevicesOpen((current) => !current);
            }
          }, connectedCountLabel),
          devicesOpen && connectedCount > 0 ? React.createElement(
            "div",
            {
              className: "dshRemoteConnectedPanel",
              id: "connected-devices",
              role: "dialog",
              "aria-label": t("currentConnectedDevices")
            },
            React.createElement("strong", { className: "dshRemoteConnectedPanelTitle" }, t("currentConnectedDevices")),
            React.createElement(
              "div",
              { className: "dshRemoteClientList", "aria-label": t("currentConnectedDevices") },
              ...connectedClients.map((client) => React.createElement(
                "div",
                {
                  key: client.deviceId,
                  className: "dshRemoteClientRow"
                },
                React.createElement(
                  "span",
                  null,
                  React.createElement("strong", null, client.name.trim() === "" ? t("unknownDevice") : client.name),
                  React.createElement("small", null, [
                    client.platform === void 0 ? void 0 : formatPlatform(client.platform),
                    connectedClientModeLabel(client.mode, t)
                  ].filter(Boolean).join(" \xB7 "))
                ),
                React.createElement("small", { className: "dshRemoteClientOnline" }, t("connected"))
              ))
            )
          ) : null
        ) : null, listedHosts = connectingHost === void 0 ? devices : [connectingHost];
        return React.createElement(
          React.Fragment,
          null,
          React.createElement(
            "div",
            { className: `dshRemoteSidebarEntry${status?.mode === "remote" ? " isActive" : ""}${props.wide ? " isWide" : " isRail"}` },
            React.createElement(status?.mode === "remote" ? "div" : "button", {
              ...status?.mode === "remote" ? {} : { type: "button", onClick: () => void show() },
              className: "dshRemoteModeButton",
              title: remoteLabel,
              "aria-label": remoteLabel
            }, React.createElement(
              "svg",
              {
                className: "dshRemoteComputerIcon",
                viewBox: "0 0 24 24",
                fill: "none",
                stroke: "currentColor",
                strokeWidth: 1.7,
                strokeLinecap: "round",
                strokeLinejoin: "round",
                "aria-hidden": !0
              },
              React.createElement("rect", { x: 3, y: 4, width: 18, height: 13, rx: 2 }),
              React.createElement("path", { d: "M8 21h8M12 17v4" })
            ), props.wide ? React.createElement(
              "span",
              { className: "dshRemoteSidebarLabel" },
              remoteLabel
            ) : null),
            (status?.mode === "remote" || status?.fellBackToLocal === !0) && props.wide ? React.createElement("button", {
              type: "button",
              className: "dshRemoteExitLink",
              disabled: busy,
              onClick: () => void openLocalWorkspaces()
            }, t("exitRemote")) : null
          ),
          open ? React.createElement("div", {
            className: "dshRemoteBackdrop",
            role: "presentation",
            onMouseDown: (event) => {
              event.target === event.currentTarget && setOpen(!1);
            }
          }, React.createElement(
            "section",
            {
              className: `dshRemotePage${selectedHost === void 0 ? "" : " hasSelectedHost"}`,
              role: "dialog",
              "aria-modal": !0,
              "aria-label": selectedHost?.name ?? t("remoteTitle")
            },
            React.createElement(
              "header",
              { className: "dshRemotePageHeader" },
              React.createElement(
                "div",
                { className: "dshRemotePageIntro" },
                React.createElement(
                  "div",
                  { className: "dshRemotePageTitleRow" },
                  React.createElement("strong", null, selectedHost?.name ?? t("remoteTitle"))
                ),
                React.createElement("p", null, selectedHostDetails ?? t("remoteDescription"))
              ),
              React.createElement(
                "div",
                { className: "dshRemotePageActions" },
                selectedHost === void 0 ? null : React.createElement("button", {
                  type: "button",
                  className: "dshRemotePageBack",
                  disabled: busy,
                  title: t("backToHosts"),
                  onClick: chooseAnotherHost
                }, t("backToHosts")),
                React.createElement("button", {
                  type: "button",
                  className: "dshRemotePageRefresh",
                  disabled: busy,
                  title: t("refreshRemote"),
                  "aria-label": t("refreshRemote"),
                  onClick: () => void refreshRemote()
                }, t("refreshRemoteShort")),
                React.createElement("button", { type: "button", className: "dshRemotePageClose", onClick: () => setOpen(!1), "aria-label": t("close") }, "\xD7")
              )
            ),
            React.createElement(
              "main",
              { className: "dshRemotePageBody" },
              status?.mode === "remote" || status?.fellBackToLocal === !0 ? React.createElement("button", {
                type: "button",
                className: "dshRemoteLocalLink",
                disabled: busy,
                onClick: () => void openLocalWorkspaces()
              }, t("openLocalWorkspaces")) : null,
              React.createElement(
                React.Fragment,
                null,
                authorizationResolved && needsAuthorization ? React.createElement(
                  "section",
                  { className: "dshRemoteEnable" },
                  React.createElement(
                    "div",
                    { className: "dshRemoteClientLogin" },
                    editingServerUrl ? React.createElement("input", {
                      type: "url",
                      value: loginServerUrl,
                      disabled: busy,
                      autoComplete: "url",
                      className: "dshRemoteServerInput",
                      placeholder: t("serverUrl"),
                      autoFocus: !0,
                      "aria-label": t("serverUrl"),
                      // Leaving the field restores the read-only line, so the
                      // editor is a transient state rather than a mode.
                      onBlur: () => setEditingServerUrl(!1),
                      onChange: (event) => setLoginServerUrl(event.target.value)
                    }) : React.createElement(
                      "div",
                      { className: "dshRemoteServerRow" },
                      // Plain text that happens to be clickable: the address
                      // stays quiet until someone reaches for it.
                      React.createElement("button", {
                        type: "button",
                        className: "dshRemoteServerValue",
                        disabled: busy,
                        title: t("changeServerUrl"),
                        "aria-label": t("changeServerUrl"),
                        onClick: () => setEditingServerUrl(!0)
                      }, loginServerUrl)
                    ),
                    React.createElement("button", {
                      type: "button",
                      disabled: busy || signingIn || loginServerUrl.trim() === "",
                      onClick: () => void signInClient()
                    }, t(signingIn ? "signingIn" : "deepseekSignIn")),
                    awaitingDeepSeek ? React.createElement(
                      "p",
                      { className: "dshRemoteServiceAddress" },
                      t(pendingAuthorizeUrl === void 0 ? "deepseekSignInRequesting" : "deepseekSignInWaiting"),
                      pendingAuthorizeUrl === void 0 ? null : React.createElement("a", {
                        href: pendingAuthorizeUrl,
                        target: "_blank",
                        rel: "noreferrer",
                        style: { marginLeft: "6px", color: "var(--dsw-alias-brand-primary)" }
                      }, t("deepseekSignInOpen"))
                    ) : null
                  )
                ) : null,
                !authorizationResolved || needsAuthorization ? null : React.createElement(
                  React.Fragment,
                  null,
                  selectedHost === void 0 ? React.createElement(
                    "section",
                    { className: "dshRemoteHosts", "aria-label": t("chooseHost") },
                    React.createElement(
                      "div",
                      { className: "dshRemoteSectionHeading" },
                      React.createElement(
                        "div",
                        { className: "dshRemoteSectionTitle" },
                        React.createElement("strong", null, t("chooseHost")),
                        status?.hostAuthorizationAvailable ? React.createElement(
                          "div",
                          { className: "dshRemoteHostControlToggle" },
                          React.createElement("span", null, t("allowControlDevice")),
                          React.createElement("input", {
                            type: "checkbox",
                            role: "switch",
                            disabled: busy,
                            "aria-label": t("allowControlDevice"),
                            checked: status.host?.authorized === !0 && status.host?.paused !== !0,
                            onChange: (event) => void setCurrentDeviceControl(event.target.checked)
                          })
                        ) : null,
                        React.createElement(
                          "div",
                          { className: "dshRemotePageActions" },
                          React.createElement("button", {
                            type: "button",
                            className: "dshRemotePageRefresh",
                            disabled: busy,
                            // Match the "allow control" label exactly: 12px/18px in the secondary label colour, so it
                            // reads as the same kind of control instead of a page-level action.
                            style: { fontWeight: "normal", fontSize: "12px", lineHeight: "18px", height: "auto", minWidth: "0", padding: "0 4px", color: "var(--dsw-alias-label-secondary)" },
                            title: t("codexRemote"),
                            "aria-label": t("codexRemote"),
                            onClick: () => setCodexOpen(!0)
                          }, t("codexRemote"))
                        ),
                        connectedMenu
                      )
                    ),
                    codexOpen ? React.createElement(CodexConnectionDetails, {
                      control: props.control,
                      t,
                      onClose: () => setCodexOpen(!1)
                    }) : null,
                    React.createElement("div", {
                      className: `dshRemoteHostList${connectingHost === void 0 ? "" : " isCollapsed"}`,
                      "aria-busy": connectingHost === void 0 ? void 0 : !0
                    }, listedHosts.length === 0 ? React.createElement("p", null, t(busy ? "checkingConnection" : "noRemoteHosts")) : listedHosts.map((device) => React.createElement(
                      "button",
                      {
                        type: "button",
                        key: device.deviceId,
                        disabled: busy || !device.online,
                        "aria-current": connectingHost?.deviceId === device.deviceId ? "true" : void 0,
                        onClick: () => void selectHost(device)
                      },
                      React.createElement(
                        "span",
                        null,
                        React.createElement("strong", null, device.name),
                        React.createElement("small", null, [
                          formatPlatform(device.platform),
                          device.harnessVersion === void 0 ? void 0 : t("harnessVersion", { version: device.harnessVersion }),
                          device.clientVersion === void 0 ? void 0 : t("pluginVersion", { version: device.clientVersion })
                        ].filter(Boolean).join(" \xB7 "))
                      ),
                      React.createElement("small", null, [t(device.online ? "online" : "offline"), device.hostControl === !1 ? t("hostRefusesControl") : void 0].filter(Boolean).join(" \xB7 "))
                    )))
                  ) : null,
                  React.createElement(RemoteProgressView, { progress, t }),
                  selectedHost === void 0 ? connectingHost === void 0 ? React.createElement(
                    React.Fragment,
                    null,
                    React.createElement(
                      "p",
                      { className: "dshRemoteHint" },
                      t(confirmingRevoke ? "revokeDeviceImpact" : confirmingSignOut ? "signOutImpact" : "selectHostHint")
                    ),
                    React.createElement(
                      "footer",
                      { className: "dshRemoteAccountFooter" },
                      confirmingSignOut ? React.createElement(
                        React.Fragment,
                        null,
                        React.createElement("button", {
                          type: "button",
                          className: "dshRemoteDiscard",
                          disabled: busy,
                          onClick: () => setConfirmingSignOut(!1)
                        }, t("cancel")),
                        React.createElement("button", {
                          type: "button",
                          className: "dshRemoteAccountExit",
                          disabled: busy,
                          onClick: () => void logoutRemote()
                        }, t("exitRemoteAccount"))
                      ) : React.createElement(
                        React.Fragment,
                        null,
                        React.createElement("span", null, status?.host?.account ?? t("account")),
                        confirmingRevoke ? React.createElement(
                          React.Fragment,
                          null,
                          React.createElement("button", {
                            type: "button",
                            className: "dshRemoteDiscard",
                            disabled: busy,
                            onClick: () => setConfirmingRevoke(!1)
                          }, t("cancel")),
                          React.createElement("button", {
                            type: "button",
                            className: "dshRemoteAccountExit",
                            disabled: busy,
                            onClick: () => void revokeRemote()
                          }, t("revokeThisDevice"))
                        ) : React.createElement("button", {
                          type: "button",
                          className: "dshRemoteAccountExit",
                          disabled: busy,
                          onClick: () => setConfirmingRevoke(!0)
                        }, t("revokeThisDevice")),
                        React.createElement("button", {
                          type: "button",
                          className: "dshRemoteAccountExit",
                          disabled: busy,
                          onClick: () => setConfirmingSignOut(!0)
                        }, t("exitRemoteAccount"))
                      )
                    )
                  ) : null : React.createElement(
                    "section",
                    { className: "dshRemoteBrowser", "aria-label": t("chooseDirectory") },
                    React.createElement(
                      "div",
                      { className: "dshRemoteSectionHeading dshRemoteWorkspaceHeading" },
                      React.createElement("strong", null, t(addingWorkspace ? workspaceBackend === "codex" ? "addCodexWorkspace" : "addRemoteWorkspace" : "existingWorkspaces")),
                      addingWorkspace ? React.createElement("button", {
                        type: "button",
                        className: "dshRemoteCancelWorkspace",
                        disabled: busy,
                        onClick: cancelAddingWorkspace
                      }, t("cancelAddWorkspace")) : React.createElement("button", {
                        type: "button",
                        className: "dshRemoteAddWorkspace",
                        disabled: busy,
                        title: t("addRemoteWorkspace"),
                        "aria-label": t("addRemoteWorkspace"),
                        onClick: () => startAddingWorkspace("harness")
                      }, React.createElement("svg", {
                        className: "dshRemoteAddWorkspaceIcon",
                        viewBox: "0 0 16 16",
                        "aria-hidden": !0,
                        focusable: !1
                      }, React.createElement("path", { d: "M8 3v10M3 8h10", fill: "none", stroke: "currentColor", strokeWidth: 1.5, strokeLinecap: "round" })))
                    ),
                    addingWorkspace ? React.createElement(
                      "div",
                      { className: "dshRemoteFolderBrowser" },
                      directory === void 0 ? React.createElement("p", null, t("loadingDirectory")) : React.createElement(
                        React.Fragment,
                        null,
                        React.createElement(
                          "nav",
                          { className: "dshRemoteCrumbs", "aria-label": t("currentDirectory") },
                          directory.crumbs.map((crumb) => React.createElement("button", {
                            type: "button",
                            key: crumb.path,
                            disabled: busy || crumb.path === directory.path,
                            onClick: () => void browseDirectory(crumb.path)
                          }, crumb.path === directory.home ? "\u2302" : crumb.name))
                        ),
                        React.createElement("div", { className: "dshRemoteFolderList" }, directory.entries.filter((entry) => !entry.hidden).length === 0 ? React.createElement("p", null, t("emptyDirectory")) : directory.entries.filter((entry) => !entry.hidden).map((entry) => React.createElement("button", {
                          type: "button",
                          key: entry.path,
                          disabled: busy,
                          onClick: () => void browseDirectory(entry.path)
                        }, React.createElement("span", { "aria-hidden": !0 }, "\u25B1"), React.createElement("span", null, entry.name)))),
                        directory.truncated ? React.createElement("small", null, t("directoryTruncated")) : null
                      )
                    ) : React.createElement(
                      React.Fragment,
                      null,
                      React.createElement(
                        "div",
                        { className: "dshRemoteWorkspaceLists" },
                        React.createElement(
                          "div",
                          { id: workspaceListId, className: "dshRemoteDirectoryList" },
                          workspaces.length === 0 ? React.createElement("p", null, t("noRemoteWorkspaces")) : visibleWorkspaces.map((workspace) => React.createElement(
                            "button",
                            {
                              type: "button",
                              key: workspace.workspaceId,
                              disabled: busy,
                              className: workspaceBackend === "harness" && path === workspace.path ? "isSelected" : "",
                              "aria-pressed": workspaceBackend === "harness" && path === workspace.path,
                              onClick: () => {
                                setWorkspaceBackend("harness"), setCodexWorkspaceId(void 0), setPath(workspace.path);
                              },
                              onDoubleClick: () => void openWorkspace({ backend: "harness", path: workspace.path })
                            },
                            React.createElement("img", { className: "dshRemoteWorkspaceIcon", src: deepSeekWorkspaceIcon, alt: "", "aria-hidden": !0 }),
                            React.createElement("span", null, workspace.title),
                            React.createElement("small", null, workspace.path)
                          )),
                          workspaces.length <= 3 || showAllWorkspaces ? null : React.createElement("button", {
                            type: "button",
                            className: "dshRemoteWorkspaceMore",
                            disabled: busy,
                            "aria-controls": workspaceListId,
                            "aria-label": t("showAllWorkspaces"),
                            onClick: () => setShowAllWorkspaces(!0)
                          }, React.createElement("span", { "aria-hidden": !0 }, "\u2026"))
                        )
                      ),
                      React.createElement(
                        "section",
                        { className: "dshRemoteCodexWorkspaceGroup" },
                        React.createElement(
                          "div",
                          {
                            id: codexWorkspaceHeadingId,
                            className: "dshRemoteWorkspaceSourceHeading"
                          },
                          React.createElement(
                            "span",
                            { className: "dshRemoteWorkspaceSourceText" },
                            React.createElement("strong", null, t("codexVirtualWorkspace"))
                          ),
                          codexAvailable ? React.createElement("button", {
                            type: "button",
                            className: "dshRemoteAddWorkspace",
                            disabled: busy,
                            title: t("addCodexWorkspace"),
                            "aria-label": t("addCodexWorkspace"),
                            onClick: () => startAddingWorkspace("codex")
                          }, React.createElement("svg", {
                            className: "dshRemoteAddWorkspaceIcon",
                            viewBox: "0 0 16 16",
                            "aria-hidden": !0,
                            focusable: !1
                          }, React.createElement("path", { d: "M8 3v10M3 8h10", fill: "none", stroke: "currentColor", strokeWidth: 1.5, strokeLinecap: "round" }))) : null
                        ),
                        React.createElement("div", {
                          id: codexWorkspaceListId,
                          className: "dshRemoteDirectoryList dshRemoteCodexWorkspaceList",
                          "aria-labelledby": codexWorkspaceHeadingId
                        }, visibleCodexWorkspaces.length === 0 ? React.createElement("p", null, codexNotice ?? t("noCodexWorkspaces")) : visibleCodexWorkspaces.map((workspace) => React.createElement(
                          "button",
                          {
                            type: "button",
                            key: workspace.workspaceId,
                            disabled: busy,
                            className: workspaceBackend === "codex" && codexWorkspaceId === workspace.workspaceId ? "isSelected" : "",
                            "aria-pressed": workspaceBackend === "codex" && codexWorkspaceId === workspace.workspaceId,
                            onClick: () => {
                              setWorkspaceBackend("codex"), setCodexWorkspaceId(workspace.workspaceId), setPath(workspace.path);
                            },
                            onDoubleClick: () => void openWorkspace({
                              backend: "codex",
                              path: workspace.path,
                              workspaceId: workspace.workspaceId
                            })
                          },
                          React.createElement("img", { className: "dshRemoteWorkspaceIcon isGpt", src: gptWorkspaceIcon, alt: "", "aria-hidden": !0 }),
                          React.createElement("span", null, workspace.title),
                          React.createElement("small", null, `${workspace.path} \xB7 ${workspace.sessionCount}`)
                        ))),
                        codexWorkspaces.length <= 3 || showAllCodexWorkspaces ? null : React.createElement("button", {
                          type: "button",
                          className: "dshRemoteWorkspaceMore",
                          disabled: busy,
                          "aria-controls": codexWorkspaceListId,
                          "aria-label": t("showAllCodexWorkspaces"),
                          onClick: () => setShowAllCodexWorkspaces(!0)
                        }, React.createElement("span", { "aria-hidden": !0 }, "\u2026"))
                      )
                    ),
                    React.createElement(
                      "footer",
                      { className: "dshRemoteOpenBar" },
                      React.createElement("div", null, React.createElement("span", null, t("currentDirectory")), React.createElement("strong", null, path || "\u2014")),
                      React.createElement("button", {
                        type: "button",
                        disabled: busy || path.trim() === "" || !addingWorkspace && workspaceBackend === "codex" && codexWorkspaceId === void 0,
                        onClick: () => void openWorkspace()
                      }, t(busy ? "openingWorkspace" : addingWorkspace ? "confirmAddWorkspace" : "openWorkspace"))
                    )
                  )
                )
              ),
              notice === void 0 ? null : React.createElement("p", { className: "dshRemoteNotice", role: "status" }, notice),
              error === void 0 || error === codexNotice ? null : React.createElement("p", { className: "dshRemoteError", role: "alert" }, error)
            )
          )) : null
        );
      }
      function CodexConnectionFields(props) {
        let { t, busy, writable, unavailable, editable, pinned, discovered, value } = props;
        return React.createElement(
          React.Fragment,
          null,
          React.createElement(
            "div",
            { className: "dshRemoteAuthorizationSetting" },
            React.createElement(
              "div",
              null,
              React.createElement("span", null, t("codexControlAllow"))
            ),
            React.createElement("input", {
              type: "checkbox",
              role: "switch",
              // Only greyed out while it is ON and the domain cannot run. A switched-off Codex also reports
              // available:false - that is the off state, not a failure - so treating it as "unavailable" locked the
              // switch and left no way to turn Codex back on.
              disabled: busy || !writable || unavailable && props.enabled,
              "aria-label": t("codexRemote"),
              checked: props.enabled,
              onChange: (event) => props.onEnabledChange(event.target.checked)
            })
          ),
          // A disabled Codex is a choice, not a fault, and neither is a launch in progress: available is false while the
          // domain starts, which used to paint a red line reading UNKNOWN for the couple of seconds every switch takes.
          // Red is for a real failure only, and a failure that carries no code says so in words instead of printing
          // UNKNOWN as if it were a code.
          props.enabled ? props.state === "starting" || props.state === "restarting" ? React.createElement("p", { className: "dshRemoteSettingsState" }, t("codexStarting")) : unavailable && props.state === "unavailable" ? React.createElement("p", { className: "dshRemoteError" }, props.reasonCode === void 0 ? t("codexUnavailableNoCode") : t(codexUnavailableKey(props.reasonCode), { code: props.reasonCode })) : null : React.createElement("p", { className: "dshRemoteSettingsState" }, t("codexDisabled")),
          React.createElement(
            "div",
            { className: "dshRemoteCodexBinary" },
            React.createElement("input", {
              type: "text",
              value: editable ? value : discovered ?? "",
              readOnly: !editable,
              // Never disabled for being unwritable: when the CLI cannot start, this field is the only way out, and a
              // failed save reports itself in the dialog instead of leaving the user with a dead control.
              disabled: busy,
              className: editable ? "" : "isDiscovered",
              placeholder: editable ? t("codexBinaryPlaceholder") : void 0,
              "aria-label": t("codexBinaryLabel"),
              onChange: (event) => props.onValueChange(event.target.value),
              onKeyDown: (event) => {
                event.key === "Enter" && editable && props.onConfirm();
              }
            }),
            editable ? React.createElement("button", {
              type: "button",
              className: "dshRemoteDiscard",
              disabled: busy,
              onClick: () => props.onConfirm()
            }, t("codexBinaryConfirm")) : null,
            pinned ? React.createElement("button", {
              type: "button",
              className: "dshRemoteDiscard",
              disabled: busy,
              onClick: () => props.onUseAuto()
            }, t("codexBinaryAuto")) : null,
            pinned || discovered === void 0 ? React.createElement("p", null, t(pinned ? "codexBinaryPinned" : "codexBinaryMissing")) : null
          )
        );
      }
      function CodexConnectionDetails(props) {
        let { control, t, onClose } = props, [view, setView] = React.useState(void 0), [enabled, setEnabled] = React.useState(!0), [binary, setBinary] = React.useState("codex"), [busy, setBusy] = React.useState(!1), [error, setError] = React.useState(void 0);
        React.useEffect(() => {
          let cancelled = !1;
          return (async () => {
            try {
              let loaded = await control("settings.get");
              if (cancelled) return;
              setView(loaded), setEnabled(loaded.config.codex?.enabled ?? !1), setBinary(loaded.config.codex?.binary ?? "codex");
            } catch (cause) {
              cancelled || setError(cause instanceof Error ? cause.message : String(cause));
            }
          })(), () => {
            cancelled = !0;
          };
        }, [control]);
        let status = view?.codexStatus, unavailable = status !== void 0 && status.available === !1, settledState = status?.state;
        React.useEffect(() => {
          if (view === void 0 || busy || settledState === "ready" || settledState === "disabled") return;
          let timer = setInterval(() => {
            control("settings.get").then((next) => {
              setView(next), setEnabled(next.config.codex?.enabled ?? !1), setBinary(next.config.codex?.binary ?? "codex");
            }).catch(() => {
            });
          }, 2e3);
          return () => {
            clearInterval(timer);
          };
        }, [control, view, busy, settledState]);
        let writable = view?.writable === !0, discovered = view?.discoveredCodexBinary, pinned = (view?.config.codex?.binary ?? "codex") !== "codex", editable = pinned || discovered === void 0, stateName = status === void 0 ? void 0 : `${(status.state ?? "unknown").slice(0, 1).toUpperCase()}${(status.state ?? "unknown").slice(1)}`, stateText = stateName === void 0 ? t("loadingSettings") : t("codexStatusLine", { state: `${stateName}${status?.error === void 0 ? "" : ` \xB7 ${status.error}`}` }), save = async (next) => {
          setBusy(!0), setError(void 0);
          try {
            let updated = await control("settings.codex.set", {
              enabled: next.enabled ?? enabled,
              ...next.binary === void 0 ? {} : { binary: next.binary }
            });
            setView(updated), setEnabled(updated.config.codex?.enabled ?? enabled), setBinary(updated.config.codex?.binary ?? binary);
          } catch (cause) {
            setError(cause instanceof Error ? cause.message : String(cause));
          } finally {
            setBusy(!1);
          }
        };
        return React.createElement(
          "div",
          { className: "dshRemoteBackdrop", role: "presentation" },
          React.createElement(
            "section",
            {
              className: "dshRemoteDialog",
              role: "dialog",
              "aria-modal": !0,
              "aria-label": t("codexRemote")
            },
            React.createElement(
              "div",
              { className: "dshRemoteHeader" },
              React.createElement("strong", null, t("codexRemote")),
              React.createElement("button", { type: "button", onClick: onClose, "aria-label": t("close") }, "\xD7")
            ),
            view === void 0 ? React.createElement("p", { className: "dshRemoteSettingsState" }, error ?? t("loadingSettings")) : React.createElement(
              React.Fragment,
              null,
              React.createElement("p", { className: "dshRemoteSettingsState" }, stateText),
              React.createElement(CodexConnectionFields, {
                t,
                enabled,
                onEnabledChange: (next) => void save({ enabled: next }),
                busy,
                writable,
                unavailable,
                state: status?.state,
                reasonCode: status?.error,
                editable,
                pinned,
                discovered,
                value: binary,
                onValueChange: (next) => setBinary(next),
                onConfirm: () => void save({ binary }),
                onUseAuto: () => void save({ binary: "" })
              }),
              // No confirm button: the switch and the command field save on their own, and a footer button could only
              // re-send the state the dialog captured when it opened - which is how pressing it turned a starting Codex
              // back off. The footer keeps the message line and nothing else.
              React.createElement(
                "div",
                { className: "dshRemoteSettingsFooter" },
                React.createElement("p", { className: error === void 0 ? "dshRemoteNotice" : "dshRemoteError" }, error ?? "")
              )
            )
          )
        );
      }
      function RemoteSessionHeaderAction(props) {
        let { t } = props, status = React.useSyncExternalStore(
          props.statusFeed.subscribe,
          props.statusFeed.getSnapshot,
          props.statusFeed.getSnapshot
        ), [routeOpen, setRouteOpen] = React.useState(!1);
        if (React.useEffect(() => {
          if (status?.mode === "remote")
            return hideLocalSessionActions();
        }, [status?.mode]), React.useEffect(() => (document.documentElement.classList.toggle(
          "dshRemoteCodexTargetActive",
          status?.mode === "remote" && status.backend === "codex"
        ), () => document.documentElement.classList.remove("dshRemoteCodexTargetActive")), [status?.mode, status?.backend]), React.useEffect(() => {
          if (!routeOpen) return;
          let closeOnEscape = (event) => {
            event.key === "Escape" && setRouteOpen(!1);
          };
          return document.addEventListener("keydown", closeOnEscape), () => document.removeEventListener("keydown", closeOnEscape);
        }, [routeOpen]), status?.mode !== "remote" && status?.reconnecting === void 0) return null;
        let transport = status.network?.webRtc?.mode ?? status.transport ?? "Disconnected", networkLabel = t(transport === "P2P" ? "remoteNetworkP2p" : transport === "TURN" ? "remoteNetworkTurn" : transport === "Relay" ? "remoteNetworkRelay" : transport === "LAN" ? "remoteNetworkLan" : "remoteNetworkOffline"), networkOnline = status.connected === !0 && transport !== "Disconnected", reconnecting = status.reconnecting !== void 0, routeVia = t(transport === "P2P" ? "connectionRouteP2p" : transport === "TURN" ? "connectionRouteTurn" : transport === "Relay" ? "connectionRouteRelay" : "connectionRouteLan"), routeViaDetail = t(transport === "P2P" ? "connectionRouteP2pDetail" : transport === "TURN" ? "connectionRouteTurnDetail" : transport === "Relay" ? "connectionRouteRelayDetail" : "connectionRouteLanDetail"), network = status.network, webRtc = network?.webRtc, controlStateLabel = network?.controlChannelState === "connecting" ? t("controlStateConnecting") : network?.controlChannelState === "open" ? t("controlStateOpen") : network?.controlChannelState === "closing" ? t("controlStateClosing") : t("controlStateClosed"), detailValue = (value) => value === void 0 || value === "" ? t("notProvided") : String(value), candidateLabel = (value) => value === "host" ? t("candidateHost") : value === "srflx" ? t("candidateSrflx") : value === "prflx" ? t("candidatePrflx") : value === "relay" ? t("candidateRelay") : detailValue(value), fact = (label, value, mono = !1) => React.createElement(
          "div",
          null,
          React.createElement("dt", null, label),
          React.createElement("dd", { className: mono ? "isMono" : void 0, title: mono ? value : void 0 }, value)
        );
        return React.createElement(
          "div",
          { className: "dshRemoteSessionHeader", role: "status" },
          React.createElement("svg", {
            viewBox: "0 0 24 24",
            fill: "none",
            stroke: "currentColor",
            strokeWidth: 1.7,
            strokeLinecap: "round",
            strokeLinejoin: "round",
            "aria-hidden": !0
          }, React.createElement("rect", { x: 3, y: 4, width: 18, height: 13, rx: 2 }), React.createElement("path", { d: "M8 21h8M12 17v4" })),
          // The label names the session being recovered when one is, because a Codex recovery has already
          // switched the carriers to the local shell by then; reading the kind off `backend` alone said
          // "remote DSH, reconnecting" for a Codex session.
          React.createElement(
            "span",
            { className: "dshRemoteModePrefix" },
            t((status.reconnecting?.kind ?? status.backend) === "codex" ? "remoteModeCodex" : "remoteModeDsh")
          ),
          React.createElement(
            "span",
            { className: "dshRemoteSessionTarget" },
            // While reconnecting the carriers may already describe the local shell, so its name must not
            // stand in for the target: name the Host the reconnect is about, or its short id.
            status.reconnecting === void 0 ? status.target?.name ?? t("host") : status.reconnecting.targetName ?? shortDeviceId(status.reconnecting.targetDeviceId)
          ),
          React.createElement("button", {
            type: "button",
            className: `dshRemoteNetwork${reconnecting ? " isReconnecting" : networkOnline ? " isOnline" : " isOffline"}`,
            title: reconnecting ? t("stopReconnecting") : networkLabel,
            // Cancelling has to stay clickable: a reconnecting session is offline by definition.
            disabled: reconnecting ? !1 : !networkOnline,
            "aria-haspopup": reconnecting ? void 0 : "dialog",
            "aria-expanded": reconnecting ? void 0 : routeOpen,
            "aria-label": reconnecting ? t("stopReconnecting") : void 0,
            onClick: () => {
              if (!reconnecting) {
                setRouteOpen((value) => !value);
                return;
              }
              setRouteOpen(!1), props.control("mode.set", { mode: "local" }).catch(() => {
              }).then(() => {
                window.location.reload();
              });
            }
          }, React.createElement("i", { "aria-hidden": !0 }), reconnecting ? t("reconnecting") : networkLabel),
          networkOnline && !reconnecting ? React.createElement("span", { className: "dshRemoteEncrypted" }, t("remoteLinkEncrypted")) : null,
          routeOpen ? React.createElement("div", {
            className: "dshRemoteRouteBackdrop",
            role: "presentation",
            onMouseDown: (event) => {
              event.target === event.currentTarget && setRouteOpen(!1);
            }
          }, React.createElement(
            "section",
            {
              className: "dshRemoteRoutePanel",
              role: "dialog",
              "aria-label": t("connectionRouteTitle")
            },
            React.createElement(
              "header",
              null,
              React.createElement("strong", null, t("connectionRouteTitle")),
              React.createElement("button", { type: "button", "aria-label": t("close"), onClick: () => setRouteOpen(!1) }, "\xD7")
            ),
            React.createElement(
              "ol",
              null,
              React.createElement(
                "li",
                null,
                React.createElement("small", null, t("connectionRouteFrom")),
                React.createElement("strong", null, network?.local.name ?? t("connectionRouteCurrentDevice")),
                network === void 0 ? null : React.createElement("span", null, `${network.local.platform} \xB7 ${shortDeviceId(network.local.deviceId)}`)
              ),
              React.createElement(
                "li",
                null,
                React.createElement("small", null, t("connectionRouteVia")),
                React.createElement("strong", null, routeVia),
                React.createElement("span", null, routeViaDetail)
              ),
              React.createElement(
                "li",
                null,
                React.createElement("small", null, t("connectionRouteTo")),
                React.createElement("strong", null, network?.remote.name ?? status.target?.name ?? t("host")),
                React.createElement("span", null, network === void 0 ? t("connectionRouteHost") : `${network.remote.platform} \xB7 ${shortDeviceId(network.remote.deviceId)}`)
              )
            ),
            network === void 0 ? null : React.createElement(
              "section",
              { className: "dshRemoteRouteSection" },
              React.createElement("h3", null, t("connectionDetailsConnection")),
              React.createElement(
                "dl",
                null,
                fact(t("connectionId"), detailValue(network.connectionId), !0),
                fact(t("connectedAt"), network.connectedAt === void 0 ? t("notProvided") : formatLocalTime(network.connectedAt)),
                fact(t("preferredTransports"), network.preferredTransports.map((value) => transportLabel(value, t)).join(" \u2192 ")),
                fact(t("controlChannel"), `WebSocket \xB7 ${controlStateLabel}`),
                fact(t("controlAddress"), network.controlChannelUrl, !0)
              )
            ),
            webRtc === void 0 ? null : React.createElement(
              "section",
              { className: "dshRemoteRouteSection" },
              React.createElement("h3", null, t("connectionDetailsWebRtc")),
              React.createElement(
                "dl",
                null,
                fact(t("peerState"), `${webRtc.connectionState} \xB7 ICE ${webRtc.iceConnectionState}`),
                fact(t("dataChannel"), detailValue(webRtc.dataChannelState)),
                fact(t("localCandidate"), candidateLabel(webRtc.localCandidateType)),
                fact(t("remoteCandidate"), candidateLabel(webRtc.remoteCandidateType)),
                fact(t("localAddress"), detailValue(webRtc.localAddress), !0),
                fact(t("remoteAddress"), detailValue(webRtc.remoteAddress), !0),
                fact(t("networkProtocol"), detailValue(webRtc.protocol?.toUpperCase())),
                fact(t("relayProtocol"), detailValue(webRtc.relayProtocol?.toUpperCase())),
                fact(t("roundTripTime"), webRtc.currentRoundTripTimeMs === void 0 ? t("notProvided") : `${webRtc.currentRoundTripTimeMs.toLocaleString()} ms`),
                fact(t("availableBitrate"), webRtc.availableOutgoingBitrate === void 0 ? t("notProvided") : formatBitrate(webRtc.availableOutgoingBitrate)),
                fact(t("bytesSent"), webRtc.bytesSent === void 0 ? t("notProvided") : formatByteSize(webRtc.bytesSent)),
                fact(t("bytesReceived"), webRtc.bytesReceived === void 0 ? t("notProvided") : formatByteSize(webRtc.bytesReceived))
              )
            ),
            React.createElement("p", null, t("connectionRouteEncrypted"))
          )) : null
        );
      }
      function hideLocalSessionActions() {
        let selector = 'button,a,[role="button"]', hiddenAttribute = "data-dsh-remote-hidden-action", localAction = /(?:open|打开).{0,12}vs\s*code|vs\s*code.{0,12}(?:open|打开)|session\s*logs?|download.{0,12}session\s*logs?|会话日志|下载.{0,8}日志/i, inspect = (root) => {
          let candidates = root instanceof Element && root.matches(selector) ? [root, ...Array.from(root.querySelectorAll(selector))] : Array.from(root.querySelectorAll(selector));
          for (let candidate of candidates) {
            if (candidate.closest(".dshRemoteSessionHeader") !== null) continue;
            let label = [
              candidate.getAttribute("aria-label"),
              candidate.getAttribute("title"),
              candidate.getAttribute("data-tooltip"),
              candidate.textContent
            ].filter(Boolean).join(" ");
            localAction.test(label) && candidate.setAttribute(hiddenAttribute, "");
          }
        };
        inspect(document.body);
        let observer = new MutationObserver((records) => {
          for (let record of records) {
            record.type === "attributes" && inspect(record.target);
            for (let node of Array.from(record.addedNodes))
              node instanceof Element && inspect(node);
          }
        });
        return observer.observe(document.body, {
          subtree: !0,
          childList: !0,
          attributes: !0,
          attributeFilter: ["aria-label", "title", "data-tooltip"]
        }), () => {
          observer.disconnect(), document.querySelectorAll(`[${hiddenAttribute}]`).forEach((element) => element.removeAttribute(hiddenAttribute));
        };
      }
      function installStyle() {
        let style = document.createElement("style");
        return style.dataset.plugin = clientModuleId, style.dataset.pluginCss = "dsh-remote", style.textContent = [
          'html.dshRemoteTargetActive button[aria-label="\u6DFB\u52A0\u5DE5\u4F5C\u533A"],html.dshRemoteTargetActive button[aria-label="Add workspace"]{display:none!important}',
          'html.dshRemoteCodexTargetActive [data-composer-card] button[aria-haspopup="listbox"][aria-label="\u6307\u4EE4"],html.dshRemoteCodexTargetActive [data-composer-card] button[aria-haspopup="listbox"][aria-label="Commands"]{display:none!important}',
          "[data-dsh-remote-hidden-action]{display:none!important}",
          // The 0.1.6 right sidebar occupies the same shell header area as the
          // remote status strip. Hide the strip while that sidebar is expanded;
          // the sidebar itself remains the active context for terminal/files.
          ":has([data-sidebar-right-open]) .dshRemoteSessionHeader{display:none!important}",
          // TerminalRecovery renders a retry action in the conversation header.
          // A failed remote terminal is reported when its tab is opened/closed;
          // keeping this background recovery action out of the top-left header
          // avoids overlapping the session controls.
          '[title^="\u6062\u590D\u7EC8\u7AEF\u5931\u8D25"],[title^="Terminal recovery failed"]{display:none!important}',
          ".dshRemoteTerminalUnavailable{box-sizing:border-box;margin:12px;padding:12px 14px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;color:var(--dsw-alias-label-secondary);background:var(--dsw-alias-bg-layer-2);font-size:13px;line-height:1.5}",
          ".dshRemoteModeButton{min-height:36px;border:0;background:transparent;color:var(--dsw-alias-label-primary);display:flex;align-items:center;gap:8px;padding:0 10px;border-radius:8px}.dshRemoteModeButton:is(button){cursor:pointer}",
          ".dshRemoteModeButton:is(button):hover{background:var(--dsw-alias-interactive-bg-hover)}",
          ".dshRemoteSidebarEntry{box-sizing:border-box;position:relative;min-width:0;display:block;overflow:hidden}.dshRemoteSidebarEntry .dshRemoteModeButton{box-sizing:border-box;width:100%;min-width:0}.dshRemoteSidebarEntry.isWide{width:calc(100% + 8px);height:34px;margin:4px -4px}.dshRemoteSidebarEntry.isWide .dshRemoteModeButton{height:34px;min-height:34px;padding:6px 48px 6px 10px;border-radius:12px}.dshRemoteSidebarEntry.isRail{width:36px;height:54px}.dshRemoteSidebarEntry.isRail .dshRemoteModeButton{width:36px;height:36px;min-height:36px;justify-content:center;gap:0;margin:8px 0 10px;padding:0;border-radius:50%}.dshRemoteSidebarEntry.isActive .dshRemoteModeButton{color:var(--dsw-alias-label-secondary);background:transparent}.dshRemoteSidebarLabel{display:block;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.dshRemoteExitLink{position:absolute;top:50%;right:10px;transform:translateY(-50%);white-space:nowrap;border:0;background:transparent;color:var(--dsw-alias-label-secondary);padding:0;font:inherit;font-size:12px;line-height:20px;cursor:pointer}.dshRemoteExitLink:hover{color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteExitLink:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px;border-radius:2px}.dshRemoteExitLink:disabled{opacity:.45;cursor:default;text-decoration:none}",
          ".dshRemoteComputerIcon{box-sizing:border-box;width:18px;height:18px;flex:0 0 18px;color:var(--dsw-alias-label-secondary)}",
          '.dshRemoteNetwork.isReconnecting{color:#f0a020;border-color:rgba(240,160,32,.55);cursor:pointer}.dshRemoteSessionHeader{box-sizing:border-box;position:fixed;z-index:25;top:12px;left:50%;transform:translateX(-50%);max-width:calc(100vw - 360px);height:28px;display:inline-flex;align-items:center;gap:7px;color:var(--dsw-alias-label-secondary);font-size:12px;white-space:nowrap;overflow:hidden}.dshRemoteSessionHeader>svg{width:15px;height:15px;flex:0 0 auto}.dshRemoteModePrefix{flex:0 0 auto}.dshRemoteSessionTarget{flex:0 1 auto;min-width:0;max-width:260px;overflow:hidden;text-overflow:ellipsis}.dshRemoteNetwork{flex:0 0 auto;border:0;background:transparent;color:inherit;font:inherit;padding:3px 2px;display:inline-flex;align-items:center;gap:5px;cursor:pointer}.dshRemoteNetwork:hover:not(:disabled){color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteNetwork:disabled{cursor:default}.dshRemoteNetwork>i{width:6px;height:6px;border-radius:50%;background:var(--dsw-alias-label-tertiary)}.dshRemoteNetwork.isOnline>i{background:var(--dsw-alias-state-success-primary)}.dshRemoteNetwork.isOffline{color:var(--dsw-alias-state-error-primary)}.dshRemoteNetwork.isOffline>i{background:currentColor}.dshRemoteEncrypted{flex:0 0 auto;color:var(--dsw-alias-label-tertiary)}.dshRemoteNetwork:focus-visible,.dshRemoteRoutePanel>header button:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px}.dshRemoteRouteBackdrop{position:fixed;inset:0;z-index:26}.dshRemoteRoutePanel{box-sizing:border-box;position:absolute;top:48px;right:28px;width:min(680px,calc(100vw - 32px));max-height:calc(100vh - 72px);overflow:auto;color:var(--dsw-alias-label-primary);background:var(--dsw-alias-bg-layer-1);border:1px solid var(--dsw-alias-border-l1);border-radius:12px;padding:16px;white-space:normal}.dshRemoteRoutePanel>header{position:sticky;top:-16px;z-index:1;display:flex;align-items:center;justify-content:space-between;margin:-16px -16px 0;padding:16px;background:var(--dsw-alias-bg-layer-1)}.dshRemoteRoutePanel>header strong{font-size:14px}.dshRemoteRoutePanel>header button{width:28px;height:28px;border:0;border-radius:7px;background:transparent;color:inherit;font-size:20px;cursor:pointer}.dshRemoteRoutePanel>header button:hover{background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteRoutePanel ol{display:flex;align-items:stretch;margin:12px 0 0;padding:0 0 16px;border-bottom:1px solid var(--dsw-alias-border-l2);list-style:none}.dshRemoteRoutePanel li{position:relative;min-width:0;flex:1;display:flex;flex-direction:column;gap:4px;padding-right:20px}.dshRemoteRoutePanel li:not(:last-child)::after{content:"\u2192";position:absolute;right:7px;top:21px;color:var(--dsw-alias-label-tertiary)}.dshRemoteRoutePanel li small{color:var(--dsw-alias-label-tertiary)}.dshRemoteRoutePanel li strong,.dshRemoteRoutePanel li span{overflow:hidden;text-overflow:ellipsis}.dshRemoteRoutePanel li strong{font-size:13px}.dshRemoteRoutePanel li span{color:var(--dsw-alias-label-secondary);font-size:11px}.dshRemoteRouteSection{padding-top:16px}.dshRemoteRouteSection h3{margin:0 0 10px;font-size:12px;font-weight:600;color:var(--dsw-alias-label-secondary)}.dshRemoteRouteSection dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px;margin:0}.dshRemoteRouteSection dl>div{min-width:0;display:grid;grid-template-columns:minmax(104px,auto) minmax(0,1fr);gap:10px;padding:7px 0;border-bottom:1px solid var(--dsw-alias-border-l2);font-size:12px;line-height:1.45}.dshRemoteRouteSection dt{color:var(--dsw-alias-label-tertiary)}.dshRemoteRouteSection dd{min-width:0;margin:0;text-align:right;overflow-wrap:anywhere}.dshRemoteRouteSection dd.isMono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11px}.dshRemoteRoutePanel>p{margin:16px 0 0;padding-top:12px;border-top:1px solid var(--dsw-alias-border-l2);color:var(--dsw-alias-label-secondary);font-size:12px;line-height:1.5}@media(max-width:620px){.dshRemoteSessionHeader{top:8px;max-width:calc(100vw - 112px)}.dshRemoteSessionHeader>svg{display:none}.dshRemoteSessionTarget{max-width:130px}.dshRemoteEncrypted{display:none}.dshRemoteRoutePanel{top:42px;right:12px;max-height:calc(100vh - 56px)}.dshRemoteRoutePanel ol{flex-direction:column;gap:18px}.dshRemoteRoutePanel li:not(:last-child)::after{content:"\u2193";top:auto;right:auto;bottom:-16px;left:3px}.dshRemoteRouteSection dl{grid-template-columns:1fr}.dshRemoteRouteSection dl>div{grid-template-columns:1fr;gap:2px}.dshRemoteRouteSection dd{text-align:left}}',
          // Wide screens keep the pill beside the header's own right-hand controls. Narrow ones centre it on
          // screen: the harness collapses that row, so anchoring to the right left it visibly off-centre. Both
          // branches keep the same 28px *outer* height (the base rule is border-box) - the border and padding
          // here once grew the box to 36px, which dropped the text 4px below the header buttons' centre line.
          ".dshRemoteSessionHeader{left:auto;right:148px;transform:none;max-width:calc(100vw - 420px)}@media(max-width:760px){.dshRemoteSessionHeader{left:50%;right:auto;transform:translateX(-50%);max-width:calc(100vw - 104px);padding:3px 8px;border:1px solid var(--dsw-alias-border-l1);border-radius:8px;background:var(--dsw-alias-bg-layer-1)}.dshRemoteModePrefix,.dshRemoteEncrypted{display:none}.dshRemoteSessionTarget{max-width:min(30vw,150px)}}",
          ".dshRemoteModeButton:focus-visible,.dshRemotePage button:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px}",
          ".dshRemotePage{width:min(720px,100%);max-height:min(760px,calc(100vh - 40px));display:flex;flex-direction:column;background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-primary);border-radius:14px;overflow:hidden;animation:dshRemotePageIn .18s cubic-bezier(.25,1,.5,1)}",
          ".dshRemotePageHeader{min-height:72px;display:flex;align-items:center;justify-content:space-between;gap:24px;padding:14px 24px;border-bottom:1px solid var(--dsw-alias-border-l2)}.dshRemotePageIntro{min-width:0;flex:1}.dshRemotePageHeader strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:18px;line-height:1.4}.dshRemotePageHeader p{min-width:0;max-width:70ch;margin:3px 0 0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--dsw-alias-label-secondary);font-size:13px;line-height:1.5}.dshRemotePageActions{flex:0 0 auto;display:flex;align-items:center;gap:4px}.dshRemotePageActions>button{height:40px;display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;border:0;border-radius:8px;background:transparent;color:inherit;line-height:1;cursor:pointer}.dshRemotePageActions>button:hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover)}.dshRemotePageActions>button:disabled{opacity:.45;cursor:default}.dshRemotePageBack,.dshRemotePageRefresh{min-width:48px;padding:0 10px;font:inherit;font-size:13px}.dshRemotePageBack{color:var(--dsw-alias-label-secondary)!important}.dshRemotePageClose{width:40px;padding:0;font-size:24px}",
          ".dshRemotePageBody{padding:24px;overflow:auto;display:flex;flex-direction:column;gap:24px}.dshRemotePageBody button{font:inherit;color:inherit}",
          ".dshRemotePageTitleRow{display:flex;align-items:center;gap:10px;min-width:0}.dshRemotePageTitleRow>strong{min-width:0;flex:0 1 auto}",
          ".dshRemoteSectionHeading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:10px}.dshRemoteSectionTitle{min-width:0;display:flex;align-items:center;gap:10px}.dshRemoteSectionTitle>strong{font-size:14px}.dshRemoteSectionActions{display:flex;align-items:center;gap:14px}.dshRemoteSectionActions>button{border:0;background:transparent;color:var(--dsw-alias-label-secondary);cursor:pointer;padding:5px 0;font-size:12px}.dshRemoteSectionActions>button:hover:not(:disabled){color:var(--dsw-alias-label-primary);text-decoration:underline}",
          ".dshRemoteSectionTitle{flex:1}.dshRemoteSectionTitle>.dshRemoteConnectedMenu{margin-left:auto}",
          '.dshRemoteAcpSetting{display:block!important;width:100%;padding:0}.dshRemoteAcpSetting>summary{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;cursor:pointer;list-style:none}.dshRemoteAcpSetting>summary::-webkit-details-marker{display:none}.dshRemoteAcpSetting>summary::after{content:"\u2304";color:var(--dsw-alias-label-secondary);transition:transform .18s ease-out}.dshRemoteAcpSetting[open]>summary::after{transform:rotate(180deg)}.dshRemoteAcpSummaryText{min-width:0}.dshRemoteAcpSummaryText strong{display:block}.dshRemoteAcpSummaryText p{margin:4px 0 0;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:1.4}.dshRemoteAcpCheckLink{grid-column:2;grid-row:1;color:var(--dsw-alias-brand-primary);font-size:12px;text-decoration:underline;text-underline-offset:3px}.dshRemoteAcpCheckLink:hover{color:var(--dsw-alias-label-primary)}.dshRemoteAcpCheckResult{grid-column:3;grid-row:1;font-size:11px;line-height:1.4;white-space:nowrap}.dshRemoteAcpCheckResult.isPassed{color:var(--dsw-alias-state-success-primary)}.dshRemoteAcpCheckResult.isFailed{color:var(--dsw-alias-state-danger-primary)}.dshRemoteAcpList{display:flex;flex-direction:column;gap:0;margin-top:12px;padding-top:0;border-top:0}.dshRemoteAcpList>.dshRemoteAuthorizationSetting{display:grid;width:100%;grid-template-columns:minmax(0,1fr) auto auto auto auto;align-items:center;column-gap:18px;line-height:1.4}.dshRemoteAcpList>.dshRemoteAuthorizationSetting>span:first-child{min-width:0;line-height:1.4}.dshRemoteAcpList>.dshRemoteAuthorizationSetting>input{grid-column:4;grid-row:1}',
          ".dshRemoteAcpAddLink{align-self:flex-start;margin-top:12px;color:var(--dsw-alias-brand-primary);font-size:12px;text-decoration:underline;text-underline-offset:3px}.dshRemoteAcpAddForm{display:grid;grid-template-columns:minmax(100px,.8fr) minmax(140px,1fr) minmax(100px,1fr) auto;gap:8px;margin-top:12px}.dshRemoteAcpAddForm input{min-width:0;height:34px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-bg-layer-2);color:inherit;padding:0 10px}.dshRemoteAcpAddForm button{border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:transparent;color:var(--dsw-alias-label-primary);padding:0 12px;cursor:pointer;font:inherit;font-size:12px}.dshRemoteAcpAddForm button:hover{background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteAcpRemoveLink{grid-column:3;grid-row:1;color:var(--dsw-alias-label-secondary);font-size:12px;text-decoration:underline;text-underline-offset:3px}.dshRemoteAcpRemoveLink:hover{color:var(--dsw-alias-state-danger-primary)}",
          ".dshRemoteAcpCheckResult.isPassed,.dshRemoteAcpCheckResult.isFailed{color:var(--dsw-alias-label-secondary)}",
          ".dshRemoteAcpList>.dshRemoteAuthorizationSetting{grid-template-columns:minmax(0,1fr) 190px 40px 56px;column-gap:18px}.dshRemoteAcpCheckCell{grid-column:2;display:flex;align-items:center;gap:10px;min-width:0}.dshRemoteAcpCheckLink,.dshRemoteAcpRemoveLink{justify-self:start}.dshRemoteAcpCheckResult{position:static}",
          ".dshRemoteAcpCheckLink.isPassed{color:var(--dsw-alias-state-success-primary)}.dshRemoteAcpCheckLink.isFailed{color:var(--dsw-alias-state-danger-primary)}.dshRemoteAcpCheckLink.isChecking{color:var(--dsw-alias-label-secondary)}",
          ".dshRemoteCancelWorkspace{min-height:36px;border:0;background:transparent;color:var(--dsw-alias-label-secondary);padding:6px 0;cursor:pointer}.dshRemoteCancelWorkspace:hover:not(:disabled){color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteCancelWorkspace:disabled{opacity:.5;cursor:default}",
          '.dshRemoteHostList{display:flex;flex-direction:column;border-top:1px solid var(--dsw-alias-border-l2);max-height:296px;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}.dshRemoteHostList.isCollapsed{max-height:none;overflow-y:visible}.dshRemoteHostList>button{min-height:58px;flex:0 0 auto;display:flex;align-items:center;justify-content:space-between;gap:16px;text-align:left;border:0;border-bottom:1px solid var(--dsw-alias-border-l2);background:transparent;padding:10px 4px;cursor:pointer}.dshRemoteHostList>button:hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteHostList>button:disabled{opacity:.5;cursor:default}.dshRemoteHostList>button[aria-current="true"]{background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteHostList>button>span{min-width:0;display:flex;flex-direction:column;gap:3px}.dshRemoteHostList>button strong{font-size:14px;font-weight:500}.dshRemoteHostList small{color:var(--dsw-alias-label-secondary);font-size:12px}',
          '.dshRemoteConnectedMenu{position:relative;flex:0 0 auto;margin-right:4px}.dshRemoteConnectedCount{appearance:none;display:inline-flex;align-items:center;justify-content:center;height:22px;border:0;border-radius:999px;background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary);box-shadow:0 1px 2px rgba(0,0,0,.18),0 0 0 1px rgba(255,255,255,.04) inset;padding:0 10px;font:inherit;font-size:11px;font-weight:500;line-height:17px;white-space:nowrap;cursor:default}.dshRemoteConnectedCount.isOnline{color:var(--dsw-alias-state-success-primary)}.dshRemoteConnectedCount.isInteractive{cursor:pointer}.dshRemoteConnectedCount.isInteractive:hover{filter:brightness(1.08)}.dshRemoteConnectedCount:disabled{opacity:1;cursor:default}.dshRemoteConnectedCount:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px}.dshRemoteConnectedPanel{position:absolute;top:calc(100% + 8px);right:0;z-index:4;width:min(320px,calc(100vw - 48px));max-height:min(280px,40vh);overflow:auto;border:1px solid var(--dsw-alias-border-l2);border-radius:12px;background:var(--dsw-alias-bg-layer-2);box-shadow:0 10px 28px rgba(0,0,0,.28);padding:10px 12px}.dshRemoteConnectedPanelTitle{display:block;margin:0 0 6px;color:var(--dsw-alias-label-primary);font-size:12px;font-weight:600}.dshRemoteClientList{display:flex;flex-direction:column}.dshRemoteClientRow{min-height:44px;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0;border-bottom:1px solid var(--dsw-alias-border-l2)}.dshRemoteClientRow:last-child{border-bottom:0}.dshRemoteClientRow>span{min-width:0;display:flex;flex-direction:column;gap:2px}.dshRemoteClientRow strong{font-size:13px;font-weight:500}.dshRemoteClientRow small{color:var(--dsw-alias-label-secondary);font-size:11px}.dshRemoteClientOnline{display:inline-flex;align-items:center;gap:6px;color:var(--dsw-alias-state-success-primary)!important}.dshRemoteClientOnline::before{content:"";width:6px;height:6px;border-radius:50%;background:currentColor}',
          ".dshRemoteConnectedCount{height:auto;border-radius:0;background:transparent;box-shadow:none;padding:0 4px;text-decoration:underline;text-decoration-color:color-mix(in srgb,currentColor 45%,transparent);text-underline-offset:3px}.dshRemoteConnectedCount.isInteractive:hover{filter:none;text-decoration-color:currentColor}.dshRemoteConnectedCount:focus-visible{outline-offset:3px;border-radius:3px}",
          '.dshRemoteProgress{display:flex;flex-direction:column;gap:8px;margin:12px 0;padding:12px 14px;border:1px solid var(--dsw-alias-border-l2);border-radius:10px;background:var(--dsw-alias-bg-layer-2)}.dshRemoteProgressHeader{display:flex;align-items:center;justify-content:space-between;gap:12px}.dshRemoteProgressHeader strong{font-size:13px;font-weight:600}.dshRemoteProgressHeader span{color:var(--dsw-alias-label-secondary);font-size:12px}.dshRemoteProgressBar{height:6px;overflow:hidden;border-radius:999px;background:var(--dsw-alias-bg-layer-3)}.dshRemoteProgressBar>span{display:block;width:100%;height:100%;border-radius:inherit;background:var(--dsw-alias-brand-primary);transform-origin:left center;transition:transform .22s ease-out}[dir="rtl"] .dshRemoteProgressBar>span{transform-origin:right center}.dshRemoteProgress p{margin:0;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:1.45}.dshRemoteProgressRoute{font-weight:500}.dshRemoteProgressRoute .isActive{color:var(--dsw-alias-state-success-primary);font-weight:700}.dshRemoteProgressRouteArrow{color:var(--dsw-alias-label-tertiary)}@media(prefers-reduced-motion:reduce){.dshRemoteProgressBar>span{transition:none}}',
          '.dshRemoteBrowser{display:flex;flex-direction:column}.dshRemoteCrumbs{display:flex;align-items:center;gap:4px;overflow:auto;padding:2px 0 10px}.dshRemoteCrumbs>button{flex:0 0 auto;border:0;background:transparent;color:var(--dsw-alias-label-secondary);padding:5px 7px;border-radius:6px;cursor:pointer}.dshRemoteCrumbs>button:not(:last-child)::after{content:" /";color:var(--dsw-alias-label-tertiary)}.dshRemoteCrumbs>button:disabled{color:var(--dsw-alias-label-primary);font-weight:600}',
          ".dshRemoteWorkspaceLists{overflow:visible}",
          ".dshRemoteDirectoryList{min-height:72px;display:flex;flex-direction:column;border-top:1px solid var(--dsw-alias-border-l2)}.dshRemoteDirectoryList>button{min-height:52px;display:grid;grid-template-columns:auto minmax(0,1fr);column-gap:10px;text-align:left;border:0;border-bottom:1px solid var(--dsw-alias-border-l2);background:transparent;padding:8px 4px;cursor:pointer}.dshRemoteDirectoryList>button:hover,.dshRemoteDirectoryList>button.isSelected{background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteDirectoryList>button.isSelected{color:var(--dsw-alias-label-primary)}.dshRemoteDirectoryList>button>span:first-child,.dshRemoteDirectoryList>button>.dshRemoteWorkspaceIcon{grid-row:1/3}.dshRemoteWorkspaceIcon{box-sizing:border-box;width:22px;height:22px;align-self:center;object-fit:contain}.dshRemoteWorkspaceIcon.isGpt{border-radius:6px}.dshRemoteDirectoryList>button>span:not(:first-child),.dshRemoteDirectoryList>button>small{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.dshRemoteDirectoryList>button>small{grid-column:2;color:var(--dsw-alias-label-secondary)}.dshRemoteDirectoryList>p,.dshRemoteHint{margin:12px 0;color:var(--dsw-alias-label-secondary);font-size:13px}",
          ".dshRemoteAddWorkspace{box-sizing:border-box;width:40px;height:40px;display:inline-grid;place-items:center;flex:0 0 auto;border:0;border-radius:8px;background:transparent;color:var(--dsw-alias-label-secondary);padding:0;cursor:pointer}.dshRemoteAddWorkspace:hover:not(:disabled){color:var(--dsw-alias-label-primary);background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteAddWorkspace:disabled{opacity:.5;cursor:default}.dshRemoteAddWorkspaceIcon{width:20px;height:20px}.dshRemoteCodexWorkspaceGroup{margin-top:16px}.dshRemoteWorkspaceSourceHeading{min-height:44px;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 4px 7px}.dshRemoteWorkspaceSourceText{min-width:0;display:flex;flex-direction:column;gap:2px}.dshRemoteWorkspaceSourceText>strong{font-size:13px}.dshRemoteWorkspaceSourceText>small{color:var(--dsw-alias-label-secondary);font-size:11px}.dshRemoteCodexWorkspaceList{min-height:0}.dshRemoteDirectoryList>.dshRemoteWorkspaceMore,.dshRemoteCodexWorkspaceGroup>.dshRemoteWorkspaceMore{box-sizing:border-box;width:100%;min-height:48px;display:flex;align-items:center;justify-content:center;border:0;border-bottom:1px solid var(--dsw-alias-border-l2);background:transparent;color:var(--dsw-alias-label-secondary);padding:8px 4px;text-align:center;font-size:16px;cursor:pointer}.dshRemoteWorkspaceMore>span{display:block;line-height:1;transform:translateY(-2px)}.dshRemoteWorkspaceMore:hover:not(:disabled){color:var(--dsw-alias-label-primary);background:transparent}.dshRemoteWorkspaceMore:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:-2px}.dshRemoteWorkspaceMore:disabled{opacity:.5;cursor:default}",
          ".dshRemotePreviewAction,.dshRemotePreviewForm{display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap}.dshRemotePreviewForm input{width:90px;color:inherit;background:var(--dsw-alias-bg-layer-1);border:1px solid var(--dsw-alias-border-l2);padding:6px}.dshRemotePreviewForm [role=alert]{max-width:360px;white-space:normal}.dshRemoteDevelopmentSettings{margin:16px 0}.dshRemoteDevelopmentSettings .dshRemotePathField{margin:8px 0}.dshRemoteDevelopmentSettings .dshRemotePortsRow{display:flex;gap:8px;align-items:stretch}.dshRemoteDevelopmentSettings .dshRemotePortsRow>input{flex:1;min-width:0;min-height:40px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-bg-layer-3);color:inherit;padding:0 12px;font:inherit}.dshRemoteDevelopmentSettings button{min-height:36px;color:inherit;background:var(--dsw-alias-bg-layer-1);border:1px solid var(--dsw-alias-border-l2);border-radius:6px;padding:6px 12px;white-space:nowrap}",
          ".dshRemoteFolderBrowser{margin-top:14px}.dshRemoteFolderBrowser>p,.dshRemoteFolderList>p{margin:12px 0;color:var(--dsw-alias-label-secondary);font-size:13px}.dshRemoteFolderList{max-height:260px;overflow:auto;border-block:1px solid var(--dsw-alias-border-l2)}.dshRemoteFolderList>button{width:100%;min-height:42px;display:flex;align-items:center;gap:9px;border:0;border-bottom:1px solid var(--dsw-alias-border-l2);background:transparent;padding:7px 6px;text-align:left;cursor:pointer}.dshRemoteFolderList>button:hover{background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteFolderBrowser>small{display:block;margin-top:8px;color:var(--dsw-alias-state-warn-label)}",
          ".dshRemotePathField{display:flex;flex-direction:column;gap:6px;margin-top:20px}.dshRemotePathField>span{font-size:13px;font-weight:600}.dshRemotePathField>input{min-height:40px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-bg-layer-3);color:inherit;padding:0 12px;font:inherit}.dshRemotePathField>small{color:var(--dsw-alias-label-secondary)}",
          ".dshRemoteOpenBar{position:sticky;bottom:-96px;display:flex;align-items:center;justify-content:space-between;gap:20px;margin-top:20px;padding:14px 0;background:var(--dsw-alias-bg-layer-1);border-top:1px solid var(--dsw-alias-border-l2)}.dshRemoteOpenBar>div{min-width:0;display:flex;flex-direction:column;gap:3px}.dshRemoteOpenBar span{color:var(--dsw-alias-label-secondary);font-size:12px}.dshRemoteOpenBar strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.dshRemoteOpenBar>button,.dshRemoteEnable>button{min-height:40px;flex:0 0 auto;border:0;border-radius:8px;background:var(--dsw-alias-label-primary);color:var(--dsw-alias-bg-layer-1);padding:8px 16px;cursor:pointer}.dshRemoteOpenBar>button:disabled,.dshRemoteEnable>button:disabled{opacity:.5;cursor:default}",
          ".dshRemoteEnable{box-sizing:border-box;width:min(440px,100%);max-width:100%;min-height:388px;margin:0 auto;display:flex;flex-direction:column;align-items:stretch;gap:10px}.dshRemoteEnable p{margin:0;color:var(--dsw-alias-label-secondary);line-height:1.5}",
          '.dshRemoteLoginTabs{width:min(440px,100%);display:flex;border-bottom:1px solid var(--dsw-alias-border-l2)}.dshRemoteLoginTabs>button{position:relative;min-height:38px;border:0;background:transparent;color:var(--dsw-alias-label-secondary);padding:6px 14px;font:inherit;font-size:13px;cursor:pointer}.dshRemoteLoginTabs>button:hover:not(:disabled){color:var(--dsw-alias-label-primary)}.dshRemoteLoginTabs>button.isActive{color:var(--dsw-alias-label-primary);font-weight:600}.dshRemoteLoginTabs>button.isActive::after{content:"";position:absolute;right:12px;bottom:-1px;left:12px;height:2px;border-radius:2px 2px 0 0;background:var(--dsw-alias-brand-primary)}.dshRemoteLoginTabs>button:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:-2px;border-radius:6px}',
          ".dshRemoteClientLogin{width:min(440px,100%);display:flex;flex-direction:column;gap:8px}.dshRemoteClientLogin input{min-height:40px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-bg-layer-3);color:inherit;padding:0 12px;font:inherit}.dshRemoteClientLogin button{align-self:flex-start;min-height:40px;border:0;border-radius:8px;background:var(--dsw-alias-label-primary);color:var(--dsw-alias-bg-layer-1);padding:8px 16px;cursor:pointer}",
          ".dshRemoteQrLogin{width:min(440px,100%);display:flex;flex-direction:column;align-items:center;gap:8px;padding:6px 0 2px;text-align:center}.dshRemoteQrLogin img,.dshRemoteQrPlaceholder{box-sizing:border-box;width:200px;height:200px;border:1px solid var(--dsw-alias-border-l2);border-radius:12px;background:#fff;padding:8px}.dshRemoteQrOpen{display:flex;flex-direction:column;align-items:center;gap:5px;color:var(--dsw-alias-label-secondary);font-size:12px;text-decoration:none}.dshRemoteQrOpen:hover{color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteQrOpen:hover img{border-color:var(--dsw-alias-label-dimmed)}.dshRemoteQrOpen:focus-visible{border-radius:12px;outline:2px solid var(--dsw-alias-brand-primary);outline-offset:3px}.dshRemoteQrPlaceholder{display:flex;align-items:center;justify-content:center;color:var(--dsw-alias-label-secondary)}.dshRemoteQrLogin>strong{font-size:14px}.dshRemoteQrLogin>p{max-width:48ch;margin:0;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:1.5}.dshRemoteQrLogin>.dshRemoteServiceAddress{margin-top:2px;color:var(--dsw-alias-label-tertiary)}.dshRemoteServerRow{display:flex;align-items:center;justify-content:center;min-width:0}.dshRemoteClientLogin .dshRemoteServerValue{align-self:center;min-height:0;min-width:0;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:0;border-radius:8px;background:transparent;color:inherit;padding:6px 10px;font:inherit;font-size:13px;cursor:pointer}.dshRemoteClientLogin .dshRemoteServerValue:hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteClientLogin .dshRemoteServerValue:disabled{cursor:default;opacity:.45}.dshRemoteClientLogin .dshRemoteServerValue:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px}.dshRemoteServerInput{text-align:center}.dshRemoteServiceAddress>a{color:var(--dsw-alias-label-secondary);text-decoration:none}.dshRemoteServiceAddress>a:hover{color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteQrLogin>button,.dshRemoteClientLogin>.dshRemoteLoginSwitch{min-height:32px;border:0;background:transparent;color:var(--dsw-alias-label-secondary);padding:4px 8px;font:inherit;font-size:12px;cursor:pointer}.dshRemoteQrLogin>button:hover,.dshRemoteClientLogin>.dshRemoteLoginSwitch:hover{color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteClientLogin>.dshRemoteLoginSwitch{align-self:flex-start;background:transparent;color:var(--dsw-alias-label-secondary);padding-left:0}",
          ".dshRemoteLoginTabs{box-sizing:border-box;width:100%}.dshRemoteLoginTabs>button{min-width:0;flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:center}.dshRemoteLoginTabs>button.isActive::after{right:0;left:0;border-radius:0}.dshRemoteClientLogin,.dshRemoteQrLogin{box-sizing:border-box;width:100%;height:300px;min-height:300px}.dshRemoteClientLogin{align-items:stretch;padding-top:16px}.dshRemoteClientLogin>button{align-self:stretch;width:100%}.dshRemoteQrLogin{padding-top:12px}",
          '.dshRemoteHostControlToggle{display:flex;align-items:center;gap:7px;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px;white-space:nowrap;cursor:default}.dshRemoteHostControlToggle>input{appearance:none;box-sizing:border-box;position:relative;width:32px;height:18px;flex:0 0 auto;margin:0;border:1px solid var(--dsw-alias-label-secondary);border-radius:999px;background:var(--dsw-alias-bg-layer-3);cursor:pointer;box-shadow:inset 0 0 0 1px var(--dsw-alias-border-l2);transition:background .16s ease-out,border-color .16s ease-out,box-shadow .16s ease-out}.dshRemoteHostControlToggle>input::after{content:"";position:absolute;top:2px;left:2px;width:12px;height:12px;border-radius:50%;background:var(--dsw-alias-label-secondary);transition:transform .16s ease-out,background .16s ease-out}.dshRemoteHostControlToggle>input:checked{border-color:var(--dsw-alias-state-success-primary);background:var(--dsw-alias-state-success-primary);box-shadow:none}.dshRemoteHostControlToggle>input:checked::after{transform:translateX(14px);background:var(--dsw-alias-bg-layer-1)}.dshRemoteHostControlToggle>input:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px}.dshRemoteHostControlToggle>input:disabled{opacity:.5;cursor:default}@media(prefers-reduced-motion:reduce){.dshRemoteHostControlToggle>input,.dshRemoteHostControlToggle>input::after{transition:none}}',
          ".dshRemoteAccountFooter{display:flex;align-items:center;justify-content:space-between;gap:12px;border-top:1px solid var(--dsw-alias-border-l2);padding-top:14px;color:var(--dsw-alias-label-secondary);font-size:12px}.dshRemoteAccountExit{flex:0 0 auto;border:0;background:transparent;color:var(--dsw-alias-label-secondary);cursor:pointer;padding:5px 0;font-size:12px;line-height:1.5;white-space:nowrap}.dshRemoteAccountExit:hover:not(:disabled){color:var(--dsw-alias-label-primary);text-decoration:underline}.dshRemoteAccountExit:disabled{opacity:.5;cursor:default;text-decoration:none}",
          ".dshRemoteLocalLink{align-self:flex-start;border:0;background:transparent;color:var(--dsw-alias-label-secondary);padding:4px 0;cursor:pointer}.dshRemoteLocalLink:hover{color:var(--dsw-alias-label-primary)}",
          "@keyframes dshRemotePageIn{from{opacity:0;transform:translateY(6px) scale(.99)}to{opacity:1;transform:none}}@media(prefers-reduced-motion:reduce){.dshRemotePage{animation:none}}@media(max-width:620px){.dshRemoteBackdrop{padding:12px}.dshRemotePage{max-height:calc(100vh - 24px)}.dshRemotePageHeader{gap:8px;padding:12px 16px}.dshRemotePage.hasSelectedHost .dshRemotePageBack{max-width:112px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.dshRemoteSectionHeading{align-items:flex-start;flex-direction:column;gap:8px}.dshRemoteWorkspaceHeading{align-items:center;flex-direction:row}.dshRemoteSectionActions{width:100%;justify-content:space-between}.dshRemotePageBody{padding:20px 16px}.dshRemoteOpenBar{align-items:flex-end}.dshRemoteOpenBar>button{min-height:48px}}",
          ".dshRemoteBackdrop{position:fixed;inset:0;z-index:1000;background:var(--dsw-alias-bg-mask-3);display:grid;place-items:center;padding:20px}",
          ".dshRemoteDialog{width:min(460px,100%);max-height:80vh;overflow:auto;background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-primary);border:1px solid var(--dsw-alias-border-l2);border-radius:14px;padding:18px;display:grid;gap:12px;box-shadow:var(--dsw-shadow-lv2)}",
          ".dshRemoteDialog button,.dshRemoteDialog input{font:inherit;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;padding:9px 10px;background:transparent;color:inherit}",
          ".dshRemoteDialog button:not(:disabled){cursor:pointer}.dshRemoteDialog button:disabled{opacity:.5}",
          ".dshRemoteHeader{display:flex;align-items:center;justify-content:space-between}.dshRemoteHeader button{border:0;font-size:22px;padding:0 6px}",
          ".dshRemoteDevices{display:grid;gap:8px}.dshRemoteDevices p{margin:4px 0;color:var(--dsw-alias-label-secondary)}",
          ".dshRemoteError{margin:0;color:var(--dsw-alias-state-error-primary)}",
          ".dshRemoteHostAccount{display:grid;gap:8px;border-top:1px solid var(--dsw-alias-border-l3);padding-top:12px}.dshRemoteHostAccount p{margin:0;color:var(--dsw-alias-label-secondary);font-size:13px}",
          ".dshRemoteLogin{display:grid;grid-template-columns:1fr 1fr;gap:8px}.dshRemoteLogin button{grid-column:1/-1}",
          ".dshRemotePluginCard{list-style:none;border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-layer-3);border-radius:12px;transition:border-color .16s,background .16s}",
          ".dshRemotePluginModern{box-sizing:border-box;width:100%;max-width:768px;box-shadow:none}.dshRemotePluginModern>.dshRemotePluginCardHeader{display:none}.dshRemotePluginModern>.dshRemotePluginCardBody{border-top:0;margin:0;padding:8px 20px}",
          ".dshRemotePluginCard:hover{border-color:var(--dsw-alias-label-dimmed)}.dshRemotePluginCard.isOpen{background:var(--dsw-alias-bg-layer-2);border-color:var(--dsw-alias-label-dimmed)}",
          ".dshRemotePluginCardHeader{display:flex;align-items:center}.dshRemotePluginCardToggle{appearance:none;width:100%;min-width:0;font:inherit;color:inherit;text-align:left;cursor:pointer;background:transparent;border:0;border-radius:12px;display:flex;align-items:center;gap:12px;padding:14px 16px}.dshRemotePluginCardToggle:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:-2px}",
          ".dshRemotePluginCardHeading{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}.dshRemotePluginCardHeading>strong{color:var(--dsw-alias-label-primary);font-size:15px;font-weight:600;line-height:1.4}.dshRemotePluginCardHeading>span{color:var(--dsw-alias-label-tertiary);font-size:13px;line-height:1.5}.dshRemotePluginCardStatus{white-space:nowrap;background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary);border-radius:999px;padding:1px 8px;font-size:11px;font-weight:500;line-height:17px}.dshRemotePluginCardStatus.isOnline{color:var(--dsw-alias-state-success-primary)}.dshRemotePluginCardStatus.isReconnecting{color:var(--dsw-alias-state-warn-label)}.dshRemotePluginCardStatus.isOffline{color:var(--dsw-alias-state-error-primary)}.dshRemotePluginCardChevron{color:var(--dsw-alias-label-tertiary);font-size:18px;line-height:14px;transition:transform .16s}.dshRemotePluginCard.isOpen .dshRemotePluginCardChevron{transform:rotate(180deg)}",
          ".dshRemotePluginCardBody{border-top:1px solid var(--dsw-alias-border-l2);margin:0 16px;padding-bottom:8px}.dshRemoteSettings{display:flex;flex-direction:column;max-width:720px}.dshRemoteSettingsTop{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:12px 0}.dshRemoteSettingsState{margin:0;color:var(--dsw-alias-label-tertiary);font-size:13px;line-height:1.5}",
          ".dshRemoteHostIdentity{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;padding:12px 0;border-top:1px solid var(--dsw-alias-border-l2);border-bottom:1px solid var(--dsw-alias-border-l2)}.dshRemoteHostIdentity>div{min-width:0;display:flex;flex-direction:column;gap:3px}.dshRemoteHostIdentity span{color:var(--dsw-alias-label-tertiary);font-size:12px}.dshRemoteHostIdentity strong,.dshRemoteHostIdentity code{min-width:0;overflow-wrap:anywhere;color:var(--dsw-alias-label-primary);font-size:13px;font-weight:500}.dshRemoteHostIdentity code{font-family:var(--dsw-font-mono,ui-monospace,SFMono-Regular,Menlo,monospace);font-weight:400}",
          ".dshRemoteField{display:flex;flex-direction:column;gap:6px;padding:12px 0}.dshRemoteField+.dshRemoteField{border-top:1px solid var(--dsw-alias-border-l2)}.dshRemoteField label{color:var(--dsw-alias-label-primary);font-size:13px;font-weight:500;line-height:1.5}.dshRemoteField input{border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-layer-3);height:34px;font:inherit;color:var(--dsw-alias-label-primary);border-radius:8px;padding:0 12px;font-size:13px;line-height:1.5}.dshRemoteField input:focus-visible{border-color:var(--dsw-alias-brand-primary);outline:none}.dshRemoteField input:disabled{color:var(--dsw-alias-label-tertiary);cursor:default}.dshRemoteField p{color:var(--dsw-alias-label-tertiary);margin:0;font-size:12px;line-height:1.5}",
          '.dshRemoteAuthorizationSetting{border-top:1px solid var(--dsw-alias-border-l2);display:flex;align-items:center;justify-content:space-between;gap:20px;padding:12px 0}.dshRemoteAuthorizationSetting>div{min-width:0}.dshRemoteAuthorizationSetting strong{font-size:13px;font-weight:500}.dshRemoteAuthorizationSetting p{margin:3px 0 0;color:var(--dsw-alias-label-tertiary);font-size:12px}.dshRemoteAuthorizationSetting>input{appearance:none;position:relative;width:38px;height:22px;flex:0 0 auto;margin:0;border:1px solid var(--dsw-alias-label-secondary);border-radius:999px;background:var(--dsw-alias-bg-layer-3);cursor:pointer;box-shadow:inset 0 0 0 1px var(--dsw-alias-border-l2);transition:background .16s ease-out,border-color .16s ease-out,box-shadow .16s ease-out}.dshRemoteAuthorizationSetting>input::after{content:"";position:absolute;top:2px;left:2px;width:16px;height:16px;border-radius:50%;background:var(--dsw-alias-label-secondary);transition:transform .16s ease-out,background .16s ease-out}.dshRemoteAuthorizationSetting>input:checked{border-color:var(--dsw-alias-state-success-primary);background:var(--dsw-alias-state-success-primary);box-shadow:none}.dshRemoteAuthorizationSetting>input:checked::after{transform:translateX(16px);background:var(--dsw-alias-bg-layer-1)}.dshRemoteAuthorizationSetting>input:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:2px}.dshRemoteAuthorizationSetting>input:disabled{opacity:.5;cursor:default}@media(prefers-reduced-motion:reduce){.dshRemoteAuthorizationSetting>input,.dshRemoteAuthorizationSetting>input::after{transition:none}}.dshRemoteCodexBinary{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:0 0 12px}.dshRemoteCodexBinary input{flex:1 1 320px;min-width:0;font:inherit;font-size:12px;padding:5px 8px;border:1px solid var(--dsw-alias-border-l2);border-radius:6px;background:transparent;color:var(--dsw-alias-label-primary)}.dshRemoteCodexBinary input.isDiscovered{color:var(--dsw-alias-label-tertiary);cursor:default}.dshRemoteCodexBinary input:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:1px}.dshRemoteCodexBinary p{flex:1 1 100%;margin:0;color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:1.5}',
          ".dshRemoteAssociation{min-width:0;flex:1;display:flex;flex-direction:column;gap:4px}.dshRemoteAssociation>span{color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:1.5}.dshRemoteAssociation strong{color:var(--dsw-alias-label-primary);font-size:14px;font-weight:500;line-height:1.5}.dshRemoteAssociation p{color:var(--dsw-alias-label-tertiary);margin:0;font-size:12px;line-height:1.5}",
          ".dshRemoteConnection{border-top:1px solid var(--dsw-alias-border-l2);display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px 0}.dshRemoteConnectionSummary{min-width:0;display:flex;flex-direction:column;gap:4px}.dshRemoteConnectionSummary>span{color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:1.5}.dshRemoteConnectionSummary strong{display:flex;align-items:center;gap:7px;color:var(--dsw-alias-label-primary);font-size:14px;font-weight:500;line-height:1.5}.dshRemoteConnectionSummary p,.dshRemoteConnectionIssue{color:var(--dsw-alias-label-tertiary);margin:0;font-size:12px;line-height:1.5}.dshRemoteConnectionDot{width:8px;height:8px;flex:0 0 auto;border-radius:999px;background:var(--dsw-alias-label-tertiary)}.dshRemoteConnectionDot.isOnline{background:var(--dsw-alias-state-success-primary)}.dshRemoteConnectionDot.isReconnecting{background:var(--dsw-alias-state-warn-primary)}.dshRemoteConnectionDot.isOffline{background:var(--dsw-alias-state-error-primary)}.dshRemoteConnectionIssue{color:var(--dsw-alias-state-error-primary);padding:0 0 12px}.dshRemoteReconnect{appearance:none;flex:0 0 auto;font:inherit;cursor:pointer;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:transparent;color:var(--dsw-alias-label-secondary);min-height:34px;padding:5px 14px;font-size:13px;line-height:1.5}.dshRemoteReconnect:hover:not(:disabled){color:var(--dsw-alias-label-primary);border-color:var(--dsw-alias-label-dimmed);background:var(--dsw-alias-interactive-bg-hover)}.dshRemoteReconnect:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:1px}.dshRemoteReconnect:disabled{opacity:.4;cursor:default}",
          ".dshRemoteSettingsFooter{border-top:1px solid var(--dsw-alias-border-l2);display:flex;justify-content:flex-end;align-items:center;gap:8px;padding:12px 0 4px}.dshRemoteSettingsFooter .dshRemoteError,.dshRemoteNotice{min-width:0;flex:1;margin:0;font-size:12px;line-height:1.5}.dshRemoteNotice{color:var(--dsw-alias-label-tertiary)}.dshRemoteDiscard,.dshRemoteSave{appearance:none;font:inherit;cursor:pointer;border:1px solid transparent;border-radius:8px;padding:5px 14px;font-size:13px;line-height:1.5}.dshRemoteDiscard{border-color:var(--dsw-alias-border-l2);color:var(--dsw-alias-label-secondary);background:transparent}.dshRemoteDiscard:hover:not(:disabled){color:var(--dsw-alias-label-primary);border-color:var(--dsw-alias-label-dimmed)}.dshRemoteSave{background:var(--dsw-alias-label-primary);color:var(--dsw-alias-bg-layer-3)}.dshRemoteDiscard:disabled,.dshRemoteSave:disabled{opacity:.4;cursor:default}.dshRemoteDiscard:focus-visible,.dshRemoteSave:focus-visible{outline:2px solid var(--dsw-alias-brand-primary);outline-offset:1px}",
          "@media(max-width:620px){.dshRemotePluginCardStatus{display:none}.dshRemoteSettingsTop{gap:10px}.dshRemoteConnection{align-items:flex-start}.dshRemoteReconnect{min-height:40px}}"
        ].join(""), document.head.append(style), () => style.remove();
      }
      function apply(ctx) {
        if (window.__DS_HARNESS_REMOTE_CLIENT_ACTIVE__) return;
        window.__DS_HARNESS_REMOTE_CLIENT_ACTIVE__ = !0, ctx.effect(() => () => {
          window.__DS_HARNESS_REMOTE_CLIENT_ACTIVE__ = !1;
        }, "ds-harness-remote: client singleton");
        let t = ctx.locale.bind(localeNamespace), controlRouteRetryAfter = 0, controlRouteBackoffIndex = 0, control = async (endpoint, payload = {}) => {
          if (endpoint === "status" && Date.now() < controlRouteRetryAfter)
            return controlRouteUnavailableStatus();
          let result;
          try {
            result = await ctx.connection.rpc.call(CONTROL_RPC_PREFIX, endpoint, payload), controlRouteRetryAfter = 0, controlRouteBackoffIndex = 0;
          } catch (reason) {
            if (!isMissingControlRoute(reason)) throw reason;
            let delayMs = controlRouteBackoffStepsMs[Math.min(controlRouteBackoffIndex, controlRouteBackoffStepsMs.length - 1)];
            if (controlRouteBackoffIndex += 1, controlRouteRetryAfter = Date.now() + delayMs, endpoint === "status") return controlRouteUnavailableStatus();
            throw new ControlRouteUnavailableError(t("remoteControlUnavailable"));
          }
          if (!result.ok) throw controlFailure(result.error, t);
          return result.value;
        }, statusFeed = createStatusFeed({
          url: STATUS_STREAM_PATH,
          readStatus: () => control("status"),
          onFallback: (reason) => {
            console.warn("ds-harness-remote: status event stream unavailable, polling status instead:", reason);
          }
        });
        ctx.effect(() => () => statusFeed.close(), "ds-harness-remote: status stream");
        let firstSnapshot = !0, wasRemoteView = !1, wasFallenBack = !1, sawFallback = !1, wasReconnecting = !1, lastFallbackReloadAt = 0, lastRecoveryReloadAt = 0, unsubscribeReconnectRefresh = statusFeed.subscribe(() => {
          let current = statusFeed.getSnapshot();
          if (current === void 0) return;
          if (firstSnapshot) {
            firstSnapshot = !1, wasRemoteView = current.mode === "remote", wasFallenBack = current.fellBackToLocal === !0, sawFallback = wasFallenBack, wasReconnecting = current.reconnecting !== void 0;
            return;
          }
          let sawRemoteViewBefore = wasRemoteView;
          if (current.mode === "remote" && (wasRemoteView = !0), current.fellBackToLocal === !0 && (sawFallback = !0), current.fellBackToLocal === !0 ? wasFallenBack || (wasFallenBack = !0, wasRemoteView && Date.now() - lastFallbackReloadAt > 2e4 && (lastFallbackReloadAt = Date.now(), window.location.reload())) : wasFallenBack = !1, current.reconnecting !== void 0) {
            wasReconnecting = !0;
            return;
          }
          wasReconnecting && (wasReconnecting = !1, current.connected === !0 && (ctx.sessions.refresh().catch(() => {
          }), (!sawRemoteViewBefore || sawFallback) && Date.now() - lastRecoveryReloadAt > 5e3 && (lastRecoveryReloadAt = Date.now(), window.location.reload())));
        });
        ctx.effect(
          () => () => unsubscribeReconnectRefresh(),
          "ds-harness-remote: refresh the Session list after a reconnect"
        ), ctx.effect(() => {
          let disposed = !1, unsubscribeWorkspaces, unsubscribeSessions, selection, opening = !1, reconcile = () => {
            if (disposed || opening || selection === void 0) return;
            let pending = selection, workspaceSnapshot = ctx.workspaces.list.getSnapshot();
            if (!workspacesReady(workspaceSnapshot) || !workspaceSnapshot.items.some((workspace) => workspace.workspaceId === pending.workspaceId)) return;
            let sessionSnapshot = ctx.sessions.list.getSnapshot();
            if (pending.backend === "codex" && pending.sessionId !== void 0 && sessionSnapshot.phase !== "ready") return;
            opening = !0, unsubscribeWorkspaces?.(), unsubscribeSessions?.(), unsubscribeWorkspaces = void 0, unsubscribeSessions = void 0, (pending.backend === "codex" && pending.sessionId !== void 0 && sessionSnapshot.ids.includes(pending.sessionId) ? Promise.resolve(pending.sessionId) : ctx.uiWorkspace.connectWorkspace(pending.workspaceId)).then(async (sessionId) => {
              disposed || (ctx.sessions.open(sessionId), await control("workspace.selection.consume", pending).catch(() => {
              }));
            }).catch((reason) => {
              disposed || console.warn("remote workspace selection failed:", reason);
            });
          };
          return control("status").then((status) => {
            let pending = status.workspaceSelection ?? storedWorkspaceSelection();
            disposed || status.mode !== "remote" || pending === void 0 || status.target?.deviceId !== pending.targetDeviceId || (selection = pending, unsubscribeWorkspaces = ctx.workspaces.list.subscribe(reconcile), unsubscribeSessions = ctx.sessions.list.subscribe(reconcile), reconcile());
          }).catch(() => {
          }), () => {
            disposed = !0, unsubscribeWorkspaces?.(), unsubscribeSessions?.();
          };
        }, "ds-harness-remote: resume selected workspace"), ctx.inject(["fileViewer"], (fileViewerContext) => {
          let viewer = fileViewerContext.get("fileViewer");
          viewer !== void 0 && fileViewerContext.effect(() => {
            let unregister, latestSaveAsAllowed = !1, latestSaveAsMaxBytes = REMOTE_FILE_SAVE_AS_MAX_BYTES, unsubscribe = statusFeed.subscribe((status) => {
              let supported = shouldUseRemoteFileViewer(status);
              latestSaveAsAllowed = shouldAllowRemoteFileSaveAs(status), latestSaveAsMaxBytes = remoteFileSaveAsMaxBytes(status), supported && unregister === void 0 ? unregister = viewer.registerContentProvider(createRemoteFileContentProvider(
                (endpoint, payload) => control(endpoint, payload),
                { saveAsAllowed: () => latestSaveAsAllowed, saveAsMaxBytes: () => latestSaveAsMaxBytes }
              )) : !supported && unregister !== void 0 && (unregister(), unregister = void 0, latestSaveAsAllowed = !1, latestSaveAsMaxBytes = REMOTE_FILE_SAVE_AS_MAX_BYTES);
            });
            return () => {
              unsubscribe(), unregister?.();
            };
          }, "ds-harness-remote: remote file viewer provider");
        }), ctx.effect(() => ctx.locale.register(localeNamespace, { zh, en }), "ds-harness-remote: dictionaries"), ctx.effect(installStyle, "ds-harness-remote: client styles"), ctx.effect(() => installTerminalAvailabilityNotice(statusFeed, t), "ds-harness-remote: terminal availability notice"), ctx.slots.inject("shell.overlay", () => ctx.slots.register({
          name: "shell.overlay",
          id: "ds-harness-remote-global-context",
          order: 20,
          locale: localeNamespace,
          inject: () => ({ control, statusFeed })
        }, RemoteSessionHeaderAction)), ctx.slots.inject("sidebar.footer.action", () => ctx.slots.register({
          name: "sidebar.footer.action",
          id: "ds-harness-remote-workspace",
          order: -20,
          locale: localeNamespace,
          inject: () => ({
            control,
            statusFeed,
            preferredQrProvider: defaultQrProvider
          })
        }, RemoteWorkspaceAction)), ctx.slots.inject("plugins.row.config", () => ctx.slots.register({
          name: "plugins.row.config",
          key: "ds-harness-remote#ds-harness-remote",
          locale: localeNamespace,
          inject: () => ({ control, statusFeed })
        }, RemotePluginOptions)), ctx.slots.inject("plugins.bundle.config", () => ctx.slots.register({
          name: "plugins.bundle.config",
          key: "ds-harness-remote",
          locale: localeNamespace,
          inject: () => ({ control, statusFeed })
        }, RemotePluginOptions));
      }
      function isMissingControlRoute(reason) {
        return reason instanceof Error && reason.message.startsWith(`transport failure for ${CONTROL_RPC_PREFIX}/`) && (reason.message.endsWith(": HTTP 404") || reason.message.endsWith(": HTTP 405"));
      }
      function installTerminalAvailabilityNotice(feed, t) {
        let latest, notice, update = () => {
          let terminal = document.querySelector("[data-sidebar-terminal]"), unavailable = latest?.mode === "remote" && latest.remoteFeatures?.terminal !== !0;
          if (!terminal || !unavailable) {
            notice?.remove(), notice = void 0;
            return;
          }
          notice !== void 0 && notice.isConnected || (notice = document.createElement("div"), notice.className = "dshRemoteTerminalUnavailable", notice.setAttribute("role", "alert"), notice.textContent = t("terminalRemoteUnavailable"), terminal.prepend(notice));
        }, unsubscribe = feed.subscribe((status) => {
          latest = status, update();
        }), observer = new MutationObserver(update);
        return observer.observe(document.body, { childList: !0, subtree: !0 }), update(), () => {
          unsubscribe(), observer.disconnect(), notice?.remove();
        };
      }
      function messageOf(reason) {
        return reason instanceof Error ? reason.message : String(reason);
      }
      function formatPlatform(value) {
        let normalized = value.toLowerCase();
        return normalized === "darwin" || normalized === "macos" ? "macOS" : normalized === "win32" || normalized === "windows" ? "Windows" : normalized === "linux" ? "Linux" : normalized === "android" ? "Android" : value;
      }
      function connectedClientModeLabel(mode, t) {
        return t(mode === "LAN" ? "remoteNetworkLan" : mode === "P2P" ? "remoteNetworkP2p" : mode === "TURN" ? "remoteNetworkTurn" : mode === "Relay" ? "remoteNetworkRelay" : "connected");
      }
      return module.exports.apply = apply, module.exports.inject = inject, module.exports;
    }
  });
})();
