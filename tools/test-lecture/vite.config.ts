import { defineConfig, type Plugin } from 'vite';

// En développement, sert /api/analyse comme le ferait Vercel.
function apiDev(): Plugin {
  return {
    name: 'api-analyse-dev',
    configureServer(server) {
      server.middlewares.use('/api/analyse', async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const c of req) chunks.push(c as Buffer);
        const { validateRequest } = await server.ssrLoadModule('/api/guard.ts');
        const { analyse, AnalyseError } = await server.ssrLoadModule('/api/analyse.ts');
        const send = (status: number, body: unknown) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body)); };
        if (req.method !== 'POST') return send(405, { error: 'methode-refusee' });
        let body: unknown;
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(400, { error: 'corps-invalide' }); }
        const v = validateRequest(body);
        if (!v.ok) return send(v.status, { error: v.error });
        try { send(200, await analyse(v.creds)); }
        catch (e) { send(502, { error: e instanceof AnalyseError ? (e as { code: string }).code : 'fournisseur-injoignable' }); }
      });
      server.middlewares.use('/api/sonde', async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const c of req) chunks.push(c as Buffer);
        const { validateRequest } = await server.ssrLoadModule('/api/guard.ts');
        const { probe, validateSamples } = await server.ssrLoadModule('/api/sonde.ts');
        const send = (status: number, body: unknown) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body)); };
        let body: { samples?: unknown };
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(400, { error: 'corps-invalide' }); }
        const v = validateRequest(body);
        if (!v.ok) return send(v.status, { error: v.error });
        const samples = validateSamples(body.samples);
        if (!samples) return send(400, { error: 'echantillons-invalides' });
        send(200, await probe(v.creds, samples));
      });
    },
  };
}

export default defineConfig({
  plugins: [apiDev()],
  test: { environment: 'node' },
} as never);
