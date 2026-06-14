// The Compose Multiplatform Wasm test bundle ships Skiko + the Compose runtime,
// which is large enough that on slower machines (CI) it can take longer than
// Karma's 30s default no-activity timeout to download and for the test runner
// to connect. Raise the relevant timeouts so the wasm browser tests stay
// reliable rather than flaking on bundle-load time.
config.set({
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 120000,
    pingTimeout: 120000,
    captureTimeout: 600000,
});
