-- One-time repair (TODO 5fc15f50): the architecture review's delta mode wrote "unveraendert seit dem letzten
-- Urteil" as the evidence of a carried-on finding, and every ingest overwrites the stored evidence with the
-- newest one, so the concrete line references were lost on the finding. They are still in its occurrences
-- (one per report): restore the most recent occurrence evidence that is not the placeholder. A finding
-- without such an occurrence keeps the placeholder. From now on audit_findings hands the evidence to the
-- prompt, which carries it on verbatim, so the placeholder does not come back.
UPDATE audit_findings
   SET evidence = (SELECT o.evidence
                     FROM audit_finding_occurrences o
                    WHERE o.finding_id = audit_findings.id
                      AND o.evidence IS NOT NULL
                      AND trim(o.evidence) <> ''
                      AND trim(o.evidence) <> 'unveraendert seit dem letzten Urteil'
                    ORDER BY o.observed_at DESC
                    LIMIT 1)
 WHERE trim(evidence) = 'unveraendert seit dem letzten Urteil'
   AND EXISTS (SELECT 1
                 FROM audit_finding_occurrences o
                WHERE o.finding_id = audit_findings.id
                  AND o.evidence IS NOT NULL
                  AND trim(o.evidence) <> ''
                  AND trim(o.evidence) <> 'unveraendert seit dem letzten Urteil');
