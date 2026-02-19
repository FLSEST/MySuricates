import { useEffect, useState } from 'react';
import { useT } from '../../i18n';

export function SecurityBanner() {
  const t = useT();
  const [securityDisabled, setSecurityDisabled] = useState(false);

  useEffect(() => {
    fetch('/api/v1/system/auth-status')
      .then((res) => res.json())
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
    <div className="bg-amber-500/90 text-white px-4 py-2 text-sm font-medium text-center">
      {t.security.warning}
    </div>
  );
}
