package com.wherelee.helpdesk.ai;

public interface EmbeddingClient {

    int dimension();

    float[] embed(String text);

    /**
     * BGE models expect an instruction prefix on the query side only.
     */
    default float[] embedQuery(String text) {
        return embed(text);
    }

    default String embedAsVector(String text) {
        return toVectorLiteral(embed(text));
    }

    default String embedQueryAsVector(String text) {
        return toVectorLiteral(embedQuery(text));
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
