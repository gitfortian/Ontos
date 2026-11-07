/** Detail routes nested under a navigation path keep their parent entry active. */
export const isNavigationActive = (pathname: string, path: string): boolean =>
  pathname === path || pathname.startsWith(`${path}/`);
