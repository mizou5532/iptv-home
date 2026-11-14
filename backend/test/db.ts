import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync, chownSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const BIN = '/usr/lib/postgresql/16/bin';
const here = dirname(fileURLToPath(import.meta.url));
const isRoot = process.getuid?.() === 0;

export interface Db {
  admin: pg.Pool;
  stop(): Promise<void>;
  newUser(opts?: { email?: string; anonymous?: boolean }): Promise<string>;
  as<T>(uid: string | null, fn: (c: pg.PoolClient) => Promise<T>): Promise<T>;
}

function run(cmd: string, args: string[]): void {
  const [c, a] = isRoot ? ['runuser', ['-u', 'postgres', '--', cmd, ...args]] : [cmd, args];
  execFileSync(c as string, a as string[], { stdio: 'pipe' });
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address() as { port: number };
      s.close(() => resolve(port));
    });
  });
}

export async function startDb(): Promise<Db> {
  const dir = mkdtempSync(join(tmpdir(), 'iptv-pg-'));
  if (isRoot) {
    const { uid, gid } = JSON.parse(
      execFileSync('sh', ['-c', `printf '{"uid":%s,"gid":%s}' $(id -u postgres) $(id -g postgres)`]).toString(),
    );
    chownSync(dir, uid, gid);
  }
  const port = await freePort();
  let started = false;
  const cleanup = () => {
    if (started) {
      try { run(`${BIN}/pg_ctl`, ['-D', `${dir}/data`, '-m', 'immediate', 'stop']); } catch { /* déjà arrêté */ }
      started = false;
    }
    rmSync(dir, { recursive: true, force: true });
  };
  try {
    run(`${BIN}/initdb`, ['-D', `${dir}/data`, '-A', 'trust', '-U', 'postgres']);
    run(`${BIN}/pg_ctl`, ['-D', `${dir}/data`, '-o', `-p ${port} -k ${dir} -c listen_addresses=`, '-l', `${dir}/log`, '-w', 'start']);
    started = true;
    const admin = new pg.Pool({ host: dir, port, user: 'postgres', database: 'postgres', max: 4 });
    const sqlFiles = [join(here, 'shim.sql')];
    const migDir = join(here, '..', 'supabase', 'migrations');
    for (const f of readdirSync(migDir).filter((n) => n.endsWith('.sql')).sort()) sqlFiles.push(join(migDir, f));
    for (const f of sqlFiles) await admin.query(readFileSync(f, 'utf8'));

    return {
      admin,
      async stop() {
        await admin.end();
        cleanup();
      },
      async newUser(opts = {}) {
        const r = await admin.query('insert into auth.users (email, is_anonymous) values ($1, $2) returning id', [
          opts.email ?? null,
          opts.anonymous ?? false,
        ]);
        return r.rows[0].id;
      },
      async as(uid, fn) {
        const c = await admin.connect();
        try {
          await c.query('begin');
          await c.query(`set local role ${uid === null ? 'anon' : 'authenticated'}`);
          const claims = JSON.stringify(uid === null ? { role: 'anon' } : { sub: uid, role: 'authenticated' });
          await c.query(`select set_config('request.jwt.claims', $1, true)`, [claims]);
          const out = await fn(c);
          await c.query('commit');
          return out;
        } catch (e) {
          await c.query('rollback').catch(() => {});
          throw e;
        } finally {
          c.release();
        }
      },
    };
  } catch (e) {
    cleanup();
    throw e;
  }
}
