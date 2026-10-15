/**
 * Port of app/src/main/java/com/deeproot/app/core/calendar/SubjectGuesser.kt — keep both tables in sync.
 * Priority: explicit subject words → chapter keywords from the plan → generic "chem" → Other.
 * Case-insensitive, whole-word matching ("inorganic" never matches the "organic" rule).
 */
import { SubjectLabel } from "../plan/parser";

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

class Rule {
  private readonly patterns: RegExp[];
  constructor(
    readonly subject: SubjectLabel,
    keywords: string[],
  ) {
    this.patterns = keywords.map((k) => new RegExp(`(?<![\\p{L}\\p{N}])${escape(k)}(?![\\p{L}\\p{N}])`, "iu"));
  }
  matches(text: string): boolean {
    return this.patterns.some((p) => p.test(text));
  }
}

const explicitRules = [
  new Rule("Inorganic", ["inorganic", "inorg", "inorganic chemistry"]),
  new Rule("Organic", ["organic", "orgo", "organic chemistry"]),
  new Rule("Physical Chem", ["physical chem", "physical chemistry", "pchem", "p chem", "phy chem"]),
  new Rule("Physics", ["physics", "phy", "phys"]),
  new Rule("Maths", ["maths", "math", "mathematics"]),
];

const chapterRules = [
  new Rule("Physical Chem", [
    "chemical kinetics", "kinetics", "thermodynamics", "chemical equilibrium", "ionic equilibrium",
    "equilibrium", "electrochemistry", "electrochem", "structure of atom", "atomic structure",
    "mole concept", "stoichiometry", "solutions", "solid state", "surface chemistry", "redox",
  ]),
  new Rule("Inorganic", [
    "chemical bonding", "bonding", "hydrogen", "s block", "s-block", "p block", "p-block", "d block",
    "d-block", "f block", "d and f block", "coordination compound", "coordination compounds",
    "coordination", "metallurgy", "salt analysis", "periodic table", "periodicity",
    "qualitative analysis",
  ]),
  new Rule("Organic", [
    "iupac", "goc", "general organic", "isomerism", "hydrocarbon", "hydrocarbons", "alkane", "alkene",
    "alkyne", "haloalkane", "haloalkanes", "haloarene", "haloarenes", "alcohol", "alcohols", "phenol",
    "ether", "amine", "amines", "aldehyde", "aldehydes", "ketone", "ketones", "carboxylic",
    "biomolecule", "biomolecules", "polymer", "polymers", "environmental chemistry",
    "chemistry in everyday life", "chemistry in life", "aromatic",
  ]),
  new Rule("Physics", [
    "centre of mass", "center of mass", "rotation", "rotational", "oscillation", "oscillations", "shm",
    "ktg", "kinetic theory", "thermal", "fluid", "fluids", "wave", "waves", "electric charges",
    "electrostatics", "electric field", "modern physics", "current electricity", "capacitor",
    "capacitors", "capacitance", "wave optics", "ray optics", "optics", "gravitation", "magnetism",
    "magnetic", "em wave", "em waves", "semiconductor", "semiconductors", "emi",
    "electromagnetic induction", "error", "errors", "units and dimensions", "kinematics", "nlm",
    "laws of motion", "work power energy", "wpe", "friction", "circular motion", "ac",
    "alternating current", "elasticity", "properties of solids",
  ]),
  new Rule("Maths", [
    "binomial", "binomial theorem", "permutation", "permutations", "combination", "combinations", "p&c",
    "pnc", "determinant", "determinants", "matrix", "matrices", "sets", "set theory", "relation",
    "relations", "function", "functions", "inverse trig", "itf", "trigonometry", "trigonometric",
    "lcd", "limits", "continuity", "differentiability", "derivative", "derivatives", "differentiation",
    "aod", "integration", "integral", "integrals", "differential equation", "differential equations",
    "straight line", "straight lines", "statistics", "circle", "circles", "solution of triangle",
    "triangle", "parabola", "ellipse", "hyperbola", "conic", "conics", "vector", "vectors", "3d",
    "three dimensional", "probability", "complex number", "complex numbers", "sequence", "series",
    "quadratic", "logarithm",
  ]),
];

const genericChemistry = new Rule("Physical Chem", ["chem", "chemistry"]);

export function guessSubject(title: string | null | undefined): SubjectLabel {
  const text = (title ?? "").trim();
  if (!text) return "Other";
  for (const rule of explicitRules) if (rule.matches(text)) return rule.subject;
  for (const rule of chapterRules) if (rule.matches(text)) return rule.subject;
  if (genericChemistry.matches(text)) return genericChemistry.subject;
  return "Other";
}
