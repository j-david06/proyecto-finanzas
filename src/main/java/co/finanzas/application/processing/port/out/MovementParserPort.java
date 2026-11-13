package co.finanzas.application.processing.port.out;
import co.finanzas.application.processing.model.ParsedMovement;
public interface MovementParserPort { ParsedMovement parse(byte[] plaintext); }
