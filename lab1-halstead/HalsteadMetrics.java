import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable result of a Halstead analysis.
 * The two maps contain the base frequency metrics f1j and f2i.
 */
public final class HalsteadMetrics {
    private final Map<String, Integer> operatorFrequencies;
    private final Map<String, Integer> operandFrequencies;

    public HalsteadMetrics(Map<String, Integer> operators,
                           Map<String, Integer> operands) {
        operatorFrequencies = Collections.unmodifiableMap(
                new LinkedHashMap<>(operators));
        operandFrequencies = Collections.unmodifiableMap(
                new LinkedHashMap<>(operands));
    }

    public Map<String, Integer> getOperatorFrequencies() {
        return operatorFrequencies;
    }

    public Map<String, Integer> getOperandFrequencies() {
        return operandFrequencies;
    }

    /** number of unique operators. */
    public int getUniqueOperatorCount() {
        return operatorFrequencies.size();
    }

    /** number of unique operands. */
    public int getUniqueOperandCount() {
        return operandFrequencies.size();
    }

    /** N1: total number of operator occurrences. */
    public int getTotalOperatorCount() {
        return operatorFrequencies.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    /** N2: total number of operand occurrences. */
    public int getTotalOperandCount() {
        return operandFrequencies.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    /** eta = eta1 + eta2: program vocabulary. */
    public int getVocabulary() {
        return getUniqueOperatorCount() + getUniqueOperandCount();
    }

    /** N = N1 + N2: program length. */
    public int getLength() {
        return getTotalOperatorCount() + getTotalOperandCount();
    }

    /** V = N * log2(eta): program volume in bits. */
    public double getVolume() {
        int vocabulary = getVocabulary();
        if (vocabulary == 0) {
            return 0.0;
        }
        return getLength() * (Math.log(vocabulary) / Math.log(2.0));
    }
}
