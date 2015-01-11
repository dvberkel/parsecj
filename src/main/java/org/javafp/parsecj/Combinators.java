package org.javafp.parsecj;

import org.javafp.data.IList;

import java.util.Optional;
import java.util.function.*;

/**
 * A set of parser combinator functions.
 * The Parser type along with retn & bind constitute a monad.
 * This is a Java implementation of this paper:
 * http://research.microsoft.com/en-us/um/people/daan/download/papers/parsec-paper.pdf
 */
public abstract class Combinators {

    public static final Void UNIT = null;

    /**
     * Construct an error Reply indicating the end of the input has been reached.
     */
    public static <S, A> Reply<S, A> endOfInput(State<S> state) {
        return Reply.<S, A>error(
            Message.Ref.of(() ->
                Message.of(state.position(), IList.empty())
            )
        );
    }

    /**
     * Monadic return function.
     * @return a parser which returns the supplied value.
     */
    public static <S, A> Parser<S, A> retn(A x) {
        return state ->
            ConsumedT.empty(
                Reply.ok(
                    x,
                    state,
                    Message.Ref.of(() ->
                        Message.of(
                            state.position(),
                            IList.empty()
                        )
                    )
                )
            );
    }

    /**
     * Monadic bind function.
     * Bind chains two parsers by creating a parser which calls the first,
     * and if that succeeds the resultant value is passed to the
     * function argument to obtain a second parser, which is then invoked.
     * @param p a parser which is called first
     * @param f a function which is passed the result of the first parser if successful,
     *          and which returns a second parser
     * @return the combined parser
     */
    public static <S, A, B> Parser<S, B> bind(
            Parser<S, ? extends A> p,
            Function<A, Parser<S, B>> f) {
        return state -> {
            final ConsumedT<S, ? extends A> cons1 = p.apply(state);
            if (cons1.isConsumed()) {
                return ConsumedT.consumed(() ->
                    cons1.getReply().<Reply<S, B>>match(
                        ok1 -> {
                            final ConsumedT<S, B> cons2 = f.apply(ok1.result).apply(ok1.rest);
                            return cons2.getReply();
                        },
                        error -> error.cast()
                    )
                );
            } else {
                return cons1.getReply().<ConsumedT<S, B>>match(
                    ok1 -> {
                        final ConsumedT<S, B> cons2 = f.apply(ok1.result).apply(ok1.rest);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return cons2.getReply().match(
                                ok2 -> Merge.mergeOk(ok2.result, ok2.rest, ok1.msg, ok2.msg),
                                error -> Merge.<S, B>mergeError(ok1.msg, error.msg)
                            );
                        }
                    },
                    error -> ConsumedT.empty(error.cast())
                );
            }
        };
    }

    /**
     * Apply the first parser, then apply the second parser and return the result.
     * Optimisation for bind(p, x -> q) - i.e. discard x, the result of the first parser, p.
     */
    public static <S, A, B> Parser<S, B> then(Parser<S, ? extends A> p, Parser<S, B> q) {
        return state -> {
            final ConsumedT<S, ? extends A> cons1 = p.apply(state);
            if (cons1.isConsumed()) {
                return ConsumedT.consumed(() ->
                    cons1.getReply().<Reply<S, B>>match(
                        ok1 -> {
                            final ConsumedT<S, B> cons2 = q.apply(ok1.rest);
                            return cons2.getReply();
                        },
                        error -> error.cast()
                    )
                );
            } else {
                return cons1.getReply().<ConsumedT<S, B>>match(
                    ok1 -> {
                        final ConsumedT<S, B> cons2 = q.apply(ok1.rest);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return cons2.getReply().match(
                                ok2 -> Merge.mergeOk(ok2.result, ok2.rest, ok1.msg, ok2.msg),
                                error2 -> Merge.<S, B>mergeError(ok1.msg, error2.msg)
                            );
                        }
                    },
                    error -> cons1.cast()
                );
            }
        };
    }

    /**
     * A parser which always fails
     */
    public static <S, A> Parser<S, A> fail() {
        return state ->
            ConsumedT.empty(
                Reply.error(
                    Message.Ref.of(() -> Message.of(state, IList.empty()))
                )
            );
    }

    /**
     * A parser which succeeds if the end of the input is reached.
     */
    public static <S> Parser<S, Void> eof() {
        return state -> {
            if (state.end()) {
                return ConsumedT.empty(
                    Reply.ok(
                        state,
                        Message.Ref.of(() -> Message.of(state, IList.of()))
                    )
                );
            } else {
                return ConsumedT.empty(
                    Reply.<S, Void>error(
                        Message.Ref.of(() -> Message.of(state, IList.of("EOF")))
                    )
                );
            }
        };
    }

    /**
     * A parser which succeeds if the next symbol passes the predicate.
     */
    public static <S> Parser<S, S> satisfy(Predicate<S> test) {
        return state -> {
            if (!state.end()) {
                final S s = state.current();
                if (test.test(s)) {
                    final State<S> newState = state.next();
                    return ConsumedT.consumed(() -> Reply.ok(
                            s,
                            newState,
                            Message.Ref.of(() ->
                                Message.of(
                                    state.position(),
                                    IList.empty()
                                )
                            )
                        )
                    );
                } else {
                    return ConsumedT.empty(
                        Reply.<S, S>error(
                            Message.Ref.of(() -> Message.of(state, IList.of("<test>")))
                        )
                    );
                }
            } else {
                return ConsumedT.empty(endOfInput(state));
            }
        };
    }

    /**
     * A parser which succeeds if the next input symbol equals the supplied value.
     */
    public static <S> Parser<S, S> satisfy(S value) {
        return state -> {
            if (!state.end()) {
                if (state.current().equals(value)) {
                    final State<S> newState = state.next();
                    return ConsumedT.consumed(() ->
                        Reply.ok(
                            state.current(),
                            newState,
                            Message.Ref.of(() -> Message.of(state, IList.empty()))
                        )
                    );
                } else {
                    return ConsumedT.empty(
                        Reply.<S, S>error(
                            Message.Ref.of(() -> Message.of(state, IList.of(value.toString())))
                        )
                    );
                }
            } else {
                return ConsumedT.empty(endOfInput(state));
            }
        };
    }

    /**
     * A parser which succeeds if the next input symbol equals the supplied value.
     * The parser replies with the argument result.
     * Equivalent to satisfy(value).then(retn(result))
     */
    public static <S, A> Parser<S, A> satisfy(S value, A result) {
        return state -> {
            if (!state.end()) {
                if (state.current().equals(value)) {
                    final State<S> newState = state.next();
                    return ConsumedT.consumed(() ->
                        Reply.ok(
                            result,
                            newState,
                            Message.Ref.of(() -> Message.of(state, IList.empty()))
                        )
                    );
                } else {
                    return ConsumedT.empty(
                        Reply.<S, A>error(
                            Message.Ref.of(() ->
                                Message.of(state, IList.of(value.toString()))
                            )
                        )
                    );
                }
            } else {
                return ConsumedT.empty(endOfInput(state));
            }
        };
    }

    /**
     * A parser which succeeds if either the first or second of the parser args succeeds.
     */
    public static <S, A> Parser<S, A> or(Parser<S, A> p, Parser<S, A> q) {
        return state -> {
            final ConsumedT<S, A> cons1 = p.apply(state);
            if (cons1.isConsumed()) {
                return cons1;
            } else {
                return cons1.getReply().match(
                    ok1 -> {
                        final ConsumedT<S, A> cons2 = q.apply(state);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return Merge.mergeOk(ok1.result, ok1.rest, ok1.msg, cons2.getReply().msg);
                        }
                    },
                    error1 -> {
                        final ConsumedT<S, A> cons2 = q.apply(state);
                        if (cons2.isConsumed()) {
                            return cons2;
                        } else {
                            return cons2.getReply().match(
                                ok2 -> Merge.mergeOk(ok2.result, ok2.rest, error1.msg, ok2.msg),
                                error2 -> Merge.<S, A>mergeError(error1.msg, error2.msg)
                            );
                        }
                    }
                );
            }
        };
    }

    /**
     * Label a parser with a readable name for more meaningful error messages.
     */
    public static <S, A> Parser<S, A> label(Parser<S, A> p, String name) {
        return state -> {
            final ConsumedT<S, A> cons = p.apply(state);
            if (cons.isConsumed()) {
                return cons;
            } else {
                return cons.getReply().match(
                    ok -> ConsumedT.empty(Reply.ok(ok.result, ok.rest, ok.msg.expect(name))),
                    error -> ConsumedT.empty(Reply.error((error.msg.expect(name))))
                );
            }
        };
    }

    /**
     * A parser which turns a parser which consumes input into one which doesn't (if it fails).
     * This allows the implementation of LL(∞) grammars.
     */
    public static <S, A> Parser<S, A> tryP(Parser<S, A> p) {
        return state -> {
            final ConsumedT<S, A> cons = p.apply(state);
            if (cons.isConsumed()) {
                return cons.getReply().match(
                    ok -> cons,
                    error -> ConsumedT.empty(error)
                );
            } else {
                return cons;
            }
        };
    }

    /**
     * A parser which attempts parser p first and if it fails then return x.
     */
    public static <S, A> Parser<S, A> option(Parser<S, A> p, A x) {
        return or(p, retn(x));
    }

    /**
     * A parser for optional values.
     */
    public static <S, A> Parser<S, Optional<A>> optionalOpt(Parser<S, A> p) {
        return option(
            p.bind(x -> retn(Optional.of(x))),
            Optional.empty()
        );
    }

    /**
     * A parser for optional values, which throws the result away.
     */
    public static <S, A> Parser<S, Void> optional(Parser<S, A> p) {
        return or(
            bind(p, x -> retn(UNIT)),
            retn(UNIT)
        );
    }

    /**
     * choice tries to apply the parsers in the list ps in order,
     * until one of them succeeds. Returns the value of the succeeding
     * parser
     */
    public static <S, A> Parser<S, A> choice(IList<Parser<S, A>> ps) {
        if (ps.tail().isEmpty()) {
            return ps.head();
        } else {
            return or(ps.head(), choice(ps.tail()));
        }
    }

    /**
     * choice tries to apply the parsers in the list ps in order,
     * until one of them succeeds. It returns the value of the succeeding
     * parser
     */
    public static <S, A> Parser<S, A> choice(Parser<S, A>... ps) {
        return choice(IList.of(ps));
    }

    /**
     * A parser for a list of zero or more values of the same type.
     */
    public static <S, A> Parser<S, IList<A>> many(Parser<S, A> p) {
        return manyAcc(p, IList.empty());
    }

    /**
     * A parser for a list of one or more values of the same type.
     */
    public static <S, A> Parser<S, IList<A>> many1(Parser<S, A> p) {
        return bind(p, x -> manyAcc(p, IList.of(x)));
    }

    private static <S, A> Parser<S, IList<A>> manyAcc(Parser<S, A> p, IList<A> acc) {
        return or(bind(p, x -> manyAcc(p, acc.add(x))), retn(acc.reverse()));
    }

    /**
     * A parser which applies the parser p zero or more times, skipping its result.
     */
    public static <S, A> Parser<S, Void> skipMany(Parser<S, A> p) {
        return or(bind(p, x -> skipMany(p)), retn(UNIT));
    }

    /**
     * A parser for an operand followed by zero or more operands (p) separated by operators (op).
     * This parser can for example be used to eliminate left recursion which typically occurs in expression grammars.
     */
    public static <S, A> Parser<S, A> chainl1(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op) {
        return bind(p, x -> rest(p, op, x));
    }

    private static <S, A> Parser<S, A> rest(
            Parser<S, A> p,
            Parser<S, BinaryOperator<A>> op,
            A x) {
        return or(
            bind(op, f -> bind(p, y -> rest(p, op, f.apply(x, y)))), retn(x)
        );
    }

    /**
     * A parser which parses zero or more occurrences of p, separated by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> sepBy(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return or(sepBy1(p, sep), retn(IList.empty()));
    }

    /**
     * A parser which parses one or more occurrences of p, separated by sep,
     * and returns a list of values returned by p.
     */
    public static <S, A, SEP> Parser<S, IList<A>> sepBy1(
            Parser<S, A> p,
            Parser<S, SEP> sep) {
        return bind(p, x ->
            bind(
                many(then(sep, p)),
                xs -> retn(xs.add(x))
            )
        );
    }

    /**
     * A parser which parses an OPEN symbol, then applies parser p, then parses a CLOSE symbol,
     * and returns the result of p.
     */
    public static <S, A, OPEN, CLOSE> Parser<S, A> between(
            Parser<S, OPEN> open,
            Parser<S, CLOSE> close,
            Parser<S, A> p) {
        return then(open, bind(p, a -> then(close, retn(a))));
    }
}
