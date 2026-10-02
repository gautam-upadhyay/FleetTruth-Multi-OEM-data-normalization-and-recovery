import { useEffect, useRef, useState } from 'react';
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { ArrowRight, Loader2 } from 'lucide-react';
import { api, clearSession } from './api';
import { Brand, Loading } from './components';
import type { User } from './types';

interface Configuration {
  local: boolean;
  issuer: string;
  clientId: string;
}
export function EnterpriseGate({
  demo,
  onLogin,
}: {
  demo: React.ReactNode;
  onLogin: (value: { token: string; user: User }) => void;
}) {
  const [config, setConfig] = useState<Configuration | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const manager = useRef<UserManager | null>(null);
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    api<Configuration>('/auth/config')
      .then(async (value) => {
        setConfig(value);
        if (value.local) return;
        const oidc = new UserManager({
          authority: value.issuer,
          client_id: value.clientId,
          redirect_uri: location.origin + '/',
          post_logout_redirect_uri: location.origin + '/',
          response_type: 'code',
          scope: 'openid profile email',
          userStore: new WebStorageStateStore({ store: sessionStorage }),
          automaticSilentRenew: false,
        });
        manager.current = oidc;
        if (new URLSearchParams(location.search).has('state')) {
          setBusy(true);
          const signedIn = await oidc.signinRedirectCallback();
          try {
            const me = await api<{ email: string; roles: string[]; tenantId: string }>('/auth/me', {
              headers: { Authorization: 'Bearer ' + signedIn.access_token },
            });
            onLogin({
              token: signedIn.access_token,
              user: {
                email: me.email,
                name: signedIn.profile.name || me.email,
                role: me.roles.includes('ADMIN')
                  ? 'ADMIN'
                  : me.roles.includes('ENGINEER')
                    ? 'ENGINEER'
                    : 'VIEWER',
                tenantId: me.tenantId,
              },
            });
            history.replaceState({}, '', location.origin + '/#/overview');
          } catch (e) {
            clearSession();
            throw e;
          }
        }
      })
      .catch((e) => setError((e as Error).message))
      .finally(() => setBusy(false));
  }, [onLogin]);
  if (config?.local) return <>{demo}</>;
  return (
    <div className="login-page">
      <header>
        <Brand />
      </header>
      <main className="login-main">
        <span className="eyebrow">FLEET OPERATIONS</span>
        <h1>Sign in to FleetTruth.</h1>
        {!config && !error ? (
          <Loading />
        ) : (
          <button
            className="button primary login-submit"
            disabled={busy || !manager.current}
            onClick={() => {
              setBusy(true);
              manager.current?.signinRedirect().catch((e) => {
                setError(e.message);
                setBusy(false);
              });
            }}
          >
            {busy ? (
              <Loader2 className="spin" size={18} />
            ) : (
              <>
                Continue with organization
                <ArrowRight size={17} />
              </>
            )}
          </button>
        )}
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
      </main>
    </div>
  );
}
