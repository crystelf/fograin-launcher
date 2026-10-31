import { Outlet, createRootRoute, createRoute } from "@tanstack/react-router";
import { AppShell } from "./AppShell";
import { HomePage } from "../features/home/HomePage";

const rootRoute = createRootRoute({
  component: RootLayout,
});

function RootLayout() {
  return (
    <AppShell>
      <Outlet />
    </AppShell>
  );
}

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: HomePage,
});

export const routeTree = rootRoute.addChildren([indexRoute]);
