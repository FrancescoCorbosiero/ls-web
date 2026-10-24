package it.logisticasanchez.web.orders;

/** The line type of the legacy dialog (OP-R4): «Pallet Generico/Colli» (legacy {@code 0}) or «Epal» ({@code 1}). */
public enum LineKind {
    GENERIC("Pallet Generico/Colli"),
    EPAL("Epal");

    private final String label;

    LineKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
