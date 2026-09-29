import { useEffect, useState } from 'react';
import { useT } from '../../i18n';
import { fetchAuthStatus } from '../../api/client';

export function SecurityBanner() {
  const t = useT();
  const [securityDisabled, setSecurityDisabled] = useState(false);

  useEffect(() => {
    // Shared, cached call — the ApiKeyGuard asks for the same thing (d89b2cc1).
    fetchAuthStatus()
      .then((data) => {
        if (data.securityEnabled === false) {
          setSecurityDisabled(true);
        }
      })
      .catch(() => {
        // Ignore errors - if we can't reach the endpoint, don't show banner
      });
  }, []);

  if (!securityDisabled) return null;

  return (
    <div className="bg-warning-subtle text-warning border-b border-warning/30 px-4 py-2 text-sm font-medium text-center">
      {t.security.warning}
    </div>
  );
}
