import { NavLink, Outlet, useLocation } from "react-router-dom";

import { isNavigationActive, NAVIGATION } from "./navigation";

const ACTIVE_CLASS = "bg-[var(--accent)] text-[var(--primary)]";
const IDLE_CLASS = "text-[var(--muted-foreground)] hover:bg-[var(--accent)]";

export default function AppLayout() {
  const { pathname } = useLocation();

  return (
    <div className="flex h-full min-h-0">
      <aside className="flex w-60 shrink-0 flex-col border-r border-[var(--border)] bg-[var(--card)]">
        <div className="border-b border-[var(--border)] px-5 py-5">
          <div className="text-base font-semibold text-[var(--foreground)]">Ontos</div>
          <div className="mt-1 text-xs text-[var(--muted-foreground)]">本体驱动问数控制台</div>
        </div>
        <nav className="flex-1 space-y-1 overflow-y-auto p-3">
          {NAVIGATION.map((item) => {
            const Icon = item.icon;
            const active = isNavigationActive(pathname, item.path);
            return (
              <NavLink
                key={item.path}
                to={item.path}
                className={`block rounded-[var(--radius)] px-3 py-2.5 ${active ? ACTIVE_CLASS : IDLE_CLASS}`}
              >
                <div className="flex items-center gap-2 text-sm font-medium">
                  <Icon size={15} aria-hidden="true" />
                  {item.label}
                </div>
                <div className="mt-1 pl-[23px] text-xs opacity-70">{item.hint}</div>
              </NavLink>
            );
          })}
        </nav>
        <div className="border-t border-[var(--border)] px-5 py-4 text-xs text-[var(--muted-foreground)]">
          Spring Boot 4.0.x · JDK 21
        </div>
      </aside>

      <main className="min-w-0 flex-1 overflow-y-auto bg-[var(--background)]">
        <Outlet />
      </main>
    </div>
  );
}
