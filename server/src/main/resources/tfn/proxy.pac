// TetherFuseNet proxy auto-config (@@VERSION@@)
// Only applications that read the operating system proxy settings use this file.
// For EVERY application, DNS and UDP included, use the whole-computer mode instead.
function FindProxyForURL(url, host) {
  if (isPlainHostName(host) || host === "localhost" || shExpMatch(host, "127.*")) {
    return "DIRECT";
  }
  return "@@PROXY_LIST@@";
}
