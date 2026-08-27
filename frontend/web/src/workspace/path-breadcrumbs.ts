interface BreadcrumbSegment {
  label: string
  path: string
}

const trimTrailingSeparators = (path: string): string => {
  if (path === '/' || /^[A-Za-z]:[\\/]$/.test(path)) return path
  return path.replace(/[\\/]+$/, '')
}

const pathLabel = (path: string): string => {
  const parts = trimTrailingSeparators(path).split(/[\\/]/).filter(Boolean)
  return parts.length > 0 ? `~ (${parts.at(-1)})` : path
}

const separatorFor = (path: string): '/' | '\\' =>
  path.includes('\\') && !path.includes('/') ? '\\' : '/'

const pathEquals = (left: string, right: string, caseInsensitive: boolean): boolean =>
  caseInsensitive ? left.toLocaleLowerCase() === right.toLocaleLowerCase() : left === right

const appendPath = (base: string, segment: string, separator: '/' | '\\'): string =>
  `${base}${base.endsWith(separator) ? '' : separator}${segment}`

export const buildBreadcrumbs = (currentPath: string, rootPath: string): BreadcrumbSegment[] => {
  if (!currentPath || !rootPath) return []

  const root = trimTrailingSeparators(rootPath)
  const current = trimTrailingSeparators(currentPath)
  const separator = separatorFor(rootPath)
  const caseInsensitive = separator === '\\'
  const breadcrumbs: BreadcrumbSegment[] = [{ label: pathLabel(root), path: rootPath }]
  if (pathEquals(current, root, caseInsensitive)) return breadcrumbs

  const boundary = root.endsWith(separator) ? root : `${root}${separator}`
  const comparableCurrent = caseInsensitive ? current.toLocaleLowerCase() : current
  const comparableBoundary = caseInsensitive ? boundary.toLocaleLowerCase() : boundary
  if (!comparableCurrent.startsWith(comparableBoundary)) return breadcrumbs

  let accumulated = root
  for (const part of current.slice(boundary.length).split(/[\\/]/).filter(Boolean)) {
    accumulated = appendPath(accumulated, part, separator)
    breadcrumbs.push({ label: part, path: accumulated })
  }
  return breadcrumbs
}
