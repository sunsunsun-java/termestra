const RUNTIME_PACKAGES = new Map([
  ['darwin-arm64', '@termestra/runtime-darwin-arm64'],
  ['darwin-x64', '@termestra/runtime-darwin-x64'],
])

export const runtimePlatform = (platform = process.platform, architecture = process.arch) =>
  `${platform}-${architecture}`

export const runtimePackageName = (platform = process.platform, architecture = process.arch) =>
  RUNTIME_PACKAGES.get(runtimePlatform(platform, architecture))
