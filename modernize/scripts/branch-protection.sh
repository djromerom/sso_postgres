#!/usr/bin/env bash
# =============================================================================
# branch-protection.sh — Aplica (o reaplica) la branch protection sobre `main`
# en djromerom/sso_postgres, dejando el repositorio con las reglas descritas
# en CONTRIBUTING.md §3.3.
#
# Lo dispara el **maintainer** manualmente, no un PR. Por seguridad este
# script se queda commiteado y se ejecuta desde la rama `main` ya mergeada:
#
#   1. Fusionar la PR de infraestructura que trae el workflow `ci.yml`.
#   2. Ejecutar este script:
#
#        gh auth login --scopes repo,workflow   # una sola vez
#        ./scripts/branch-protection.sh
#
#   3. La próxima PR contra `main` ya estará sujeta a los checks listados
#      en `required_status_checks.contexts`.
#
# Idempotente: `gh api -X PATCH` reemplaza la configuración existente. Se
# puede correr de nuevo para actualizar la lista de checks cuando entren
# checks nuevos (p.ej. cuando se añada `admin-ui-e2e`).
#
# Requisitos: `gh` ≥ 2.40 autenticado con scope `repo`.
# =============================================================================
set -euo pipefail

REPO="djromerom/sso_postgres"
BRANCH="main"

# El payload exacto. Cualquier cambio a la política se hace modificando
# este archivo, NO editando flags de la CLI.
PAYLOAD=$(cat <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "maven-common",
      "maven-auth-center",
      "maven-sso-admin",
      "maven-api-gateway",
      "admin-ui-typecheck",
      "admin-ui-test",
      "admin-ui-lint",
      "admin-ui-build"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismissal_restrictions": {},
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1,
    "require_last_push_approval": false
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": false
}
JSON
)

echo "→ Aplicando branch protection a ${REPO}:${BRANCH}..."
echo
echo "${PAYLOAD}" | gh api \
  -X PATCH \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "/repos/${REPO}/branches/${BRANCH}/protection" \
  --input -

echo
echo "→ Verificando la configuración aplicada..."
gh api "/repos/${REPO}/branches/${BRANCH}/protection" \
  | jq '{
    required_status_checks: (.required_status_checks.contexts),
    required_approving_review_count: .required_pull_request_reviews.required_approving_review_count,
    dismiss_stale_reviews: .required_pull_request_reviews.dismiss_stale_reviews,
    required_linear_history: .required_linear_history,
    allow_force_pushes: .allow_force_pushes,
    allow_deletions: .allow_deletions,
    enforce_admins: .enforce_admins,
    required_conversation_resolution: .required_conversation_resolution
  }'

echo
echo "✓ Listo. Próxima PR contra '${BRANCH}' requiere los checks listados y 1 review."
