package dev.termestra.team.application.port.out;

import dev.termestra.team.domain.model.Dispatch;
public record StoredDispatch(long sequence, Dispatch dispatch) { }
