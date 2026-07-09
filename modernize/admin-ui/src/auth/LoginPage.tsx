import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "./useAuth";

interface LocationState {
  from?: string;
}

/**
 * Login form. POSTs credentials to /api/auth/login; the backend
 * returns the access token in the body and sets the refresh cookie
 * in the response headers. On success we navigate to where the
 * user was trying to go, or /admin by default.
 *
 * <p>The login identifier is the user's email — the legacy
 * {@code username} column is gone since the V12 migration. The
 * field still has {@code autoComplete="email"} so password
 * managers fill it correctly.
 */
export function LoginPage() {
  const { status, login, error, clearError } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as LocationState | null)?.from ?? "/admin";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (status === "authenticated") {
    return <Navigate to={from} replace />;
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitting(true);
    try {
      const ok = await login(email, password);
      if (ok) navigate(from, { replace: true });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
        aria-label="Login form"
      >
        <h1 className="mb-1 text-xl font-semibold text-slate-900">sso-admin</h1>
        <p className="mb-6 text-sm text-slate-500">Inicia sesión para continuar.</p>

        {error ? (
          <div
            role="alert"
            className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            onClick={clearError}
          >
            {error}
          </div>
        ) : null}

        <label className="mb-3 block text-sm">
          <span className="mb-1 block font-medium text-slate-700">Email</span>
          <input
            type="email"
            inputMode="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 outline-none focus:border-sky-500 focus:ring-1 focus:ring-sky-500"
          />
        </label>

        <label className="mb-4 block text-sm">
          <span className="mb-1 block font-medium text-slate-700">Contraseña</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded border border-slate-300 px-3 py-2 outline-none focus:border-sky-500 focus:ring-1 focus:ring-sky-500"
          />
        </label>

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded bg-sky-600 px-3 py-2 font-medium text-white hover:bg-sky-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {submitting ? "Entrando…" : "Entrar"}
        </button>

        <Link
          to="/forgot-password"
          className="mt-4 block text-center text-sm text-sky-600 hover:underline"
        >
          ¿Olvidaste tu contraseña?
        </Link>
      </form>
    </main>
  );
}
