# ConflictCourt Web (Next.js)

This app is the JCEF-hosted UI for the ConflictCourt IntelliJ plugin.

## Run locally

```bash
cd web
npm install
npm run dev
```

## Deploy to Vercel

1. Import the `web/` directory as a Vercel project (Framework: Next.js).
2. Deploy.
3. Set plugin env var `CONFLICTCOURT_WEB_URL` to your Vercel URL.

If the Vercel dashboard root-directory picker does not show `web/`:

1. Ensure the `web/` folder is committed and pushed to the branch Vercel imports.
2. Import the repo, then manually set **Root Directory** to `web`.
3. If UI still fails, deploy from CLI instead:

```bash
cd web
npx vercel
```

Then promote to production:

```bash
npx vercel --prod
```

## Bridge Contract

The plugin injects `window.conflictCourtBridge` with:

- `getCurrentConflictState(): Promise<HostState>`
- `runBranchCheck(head: string, incoming: string): Promise<{ ok, message, conflictCount }>`
- `applyResolvedCode(conflictId: string, mergedCode: string): Promise<{ ok, message }>`
- `subscribe(handler): () => void` for live updates.

Also:

- `window.__conflictCourtState` contains latest host payload.
- `window` receives `conflictcourt:state` and `conflictcourt:bridge-ready` events.

## Notes

- `applyResolvedCode` currently applies only `active_editor` conflicts.
- `branch_preview` conflicts are read-only previews.
