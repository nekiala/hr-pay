// Release notes, newest first. The version matches the server's (backend build.gradle.kts).
import { language } from './i18n'

export type Release = { version: string; date: string; fr: string[]; en: string[] }

export const RELEASES: Release[] = [
  {
    version: '0.17.0', date: '2026-11-07',
    fr: ['Guide de mise en place d’une nouvelle entreprise.', 'Centre d’aide en français et en anglais, avec un lien d’aide sur chaque page.', 'Signaler un problème depuis l’application, et suivre la réponse du support.', 'Conditions d’utilisation et accord de traitement des données acceptés par l’administrateur.', 'Page d’état publique, numéro de version et nouveautés.', 'Tableaux de bord : effectif, rotation, absence et coût de la paie par direction.', 'Demande d’essai depuis le site, approuvée par le fournisseur.'],
    en: ['A set-up guide for a new company.', 'A help centre in French and English, with a help link on each page.', 'Report a problem from the app, and follow support’s answer.', 'Terms of service and data processing agreement accepted by the administrator.', 'A public status page, the version number and what’s new.', 'Dashboards: headcount, turnover, absence and payroll cost by direction.', 'Trial requests from the website, approved by the provider.'],
  },
  {
    version: '0.16.0', date: '2026-11-07',
    fr: ['Embauches et départs : listes de contrôle, documents, équipements.', 'Lettres et contrats signés avec un code à usage unique ; attestations sur demande.', 'Application installable sur le téléphone, notifications et pointage hors connexion.', 'Mot de passe oublié, et réinitialisation d’un administrateur par le fournisseur.'],
    en: ['Hiring and departures: checklists, documents, equipment.', 'Letters and contracts signed with a one-time code; certificates on request.', 'An app installable on the phone, notifications and offline check-in.', 'Forgotten password, and the provider’s reset of an administrator.'],
  },
  {
    version: '0.15.0', date: '2026-11-07',
    fr: ['Import Excel des employés, contrats, salaires et soldes.', 'API documentée, webhooks et pointeuses ZKTeco.', 'Mises en page des fichiers de paiement et de comptabilité.'],
    en: ['Excel import of employees, contracts, salaries and balances.', 'A documented API, webhooks and ZKTeco time clocks.', 'Layouts for payment and accounting files.'],
  },
  {
    version: '0.14.0', date: '2026-11-07',
    fr: ['Décompte final de départ, corrections après clôture et rappels de salaire.', 'Écritures comptables SYSCOHADA.'],
    en: ['Final settlement on departure, corrections after closing and back pay.', 'SYSCOHADA accounting entries.'],
  },
  {
    version: '0.13.0', date: '2026-11-07',
    fr: ['Toute l’application en français, l’anglais conservé.', 'Packs pays : RDC et République du Congo.'],
    en: ['The whole app in French, English kept.', 'Country packs: DRC and Republic of the Congo.'],
  },
  {
    version: '0.12.0', date: '2026-11-07',
    fr: ['Deuxième étape à la connexion, connexion Microsoft 365, chiffrement des numéros sensibles, droits sur les données personnelles.'],
    en: ['A second sign-in step, Microsoft 365 sign-in, encryption of sensitive numbers, personal data rights.'],
  },
  {
    version: '0.11.0', date: '2026-11-07',
    fr: ['Abonnements, factures et accès du support avec l’accord du client.'],
    en: ['Subscriptions, invoices and support access with the customer’s consent.'],
  },
  {
    version: '0.10.0', date: '2026-11-07',
    fr: ['Plusieurs entreprises sur une installation, et licences pour l’installation chez le client.'],
    en: ['Several companies on one installation, and licences for installing at the customer’s.'],
  },
  {
    version: '0.9.0', date: '2026-11-07',
    fr: ['Prêt pour la production : image Docker, sauvegardes, surveillance.'],
    en: ['Ready for production: Docker image, backups, monitoring.'],
  },
]

export const notes = (r: Release) => (language() === 'fr' ? r.fr : r.en)
