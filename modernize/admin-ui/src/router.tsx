import { createBrowserRouter, Navigate } from "react-router-dom";
import { ActivatePage } from "@/auth/ActivatePage";
import { LoginPage } from "@/auth/LoginPage";
import { RequireAuth } from "@/auth/RequireAuth";
import { RestorePasswordPage } from "@/auth/RestorePasswordPage";
import { App } from "@/App";
import { GroupsListPage } from "@/pages/groups/GroupsListPage";
import { MicroservicesListPage } from "@/pages/microservices/MicroservicesListPage";
import { QueriesCatalogPage } from "@/pages/queries/QueriesCatalogPage";
import { QueriesAdminPage } from "@/pages/queries/QueriesAdminPage";
import { EndpointsListPage } from "@/pages/endpoints/EndpointsListPage";
import { RoutesListPage } from "@/pages/routes/RoutesListPage";
import { RolesListPage } from "@/pages/roles/RolesListPage";
import { UsersListPage } from "@/pages/users/UsersListPage";
import { AppsListPage } from "@/pages/apps/AppsListPage";
import { WritesListPage } from "@/pages/writes/WritesListPage";

/**
 * Router. Public routes: /login, /activate (legacy top-level for
 * already-sent emails), /admin/activate and
 * /admin/restore-password (the SPA-under-admin paths the email
 * links now point at). Everything else under /admin is gated
 * by {@link RequireAuth}. We use the data router so loaders
 * can be added later for prefetch.
 *
 * Note: in production, the api-gateway serves the SPA at /admin/**
 * and falls back to index.html for any unknown path. So the URL
 * stays /admin/users, /admin/roles, etc. The /admin/activate and
 * /admin/restore-password public routes MUST be declared above
 * the gated /admin parent so the more specific paths win
 * matching before the auth gate runs.
 */
export const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  // Legacy top-level route — emails sent before this branch
  // landed link here. Kept for one cycle of backward compat;
  // to be deleted once the next batch of activation emails
  // has been issued pointing at /admin/activate.
  { path: "/activate", element: <ActivatePage /> },
  // New SPA-under-admin activation route — the URL the
  // activation email links point at. Hosts the password
  // form that POSTs {token, password} to
  // /sso-admin/activateAccount (see ActivatePage).
  { path: "/admin/activate", element: <ActivatePage /> },
  // New SPA-under-admin restore-password route. Hosts the
  // password form that POSTs {token, password} to
  // /sso-admin/restorePassword (see RestorePasswordPage).
  { path: "/admin/restore-password", element: <RestorePasswordPage /> },
  {
    path: "/admin",
    element: <RequireAuth />,
    children: [
      {
        element: <App />,
        children: [
          { index: true, element: <Navigate to="users" replace /> },
          { path: "users", element: <UsersListPage /> },
          { path: "roles", element: <RolesListPage /> },
          { path: "groups", element: <GroupsListPage /> },
          { path: "microservices", element: <MicroservicesListPage /> },
          { path: "queries", element: <QueriesCatalogPage /> },
          { path: "query-catalog", element: <QueriesAdminPage /> },
          { path: "query-services", element: <Navigate to="microservices" replace /> },
          { path: "dynamic-crud", element: <Navigate to="queries" replace /> },
          { path: "endpoints", element: <EndpointsListPage /> },
          { path: "routes", element: <RoutesListPage /> },
          { path: "apps", element: <AppsListPage /> },
          { path: "writes", element: <WritesListPage /> },
        ],
      },
    ],
  },
  { path: "/", element: <Navigate to="/admin" replace /> },
  { path: "*", element: <Navigate to="/admin" replace /> },
]);
