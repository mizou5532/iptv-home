export type AnalyseErrorCode = 'fournisseur-injoignable' | 'fournisseur-refuse' | 'adresse-refusee' | 'delai-analyse';

export class AnalyseError extends Error {
  constructor(public readonly code: AnalyseErrorCode) { super(code); }
}
