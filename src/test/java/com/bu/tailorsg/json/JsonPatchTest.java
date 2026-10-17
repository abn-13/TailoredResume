package com.bu.tailorsg.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cases A.1 to A.16 are the worked examples in RFC 6902 Appendix A (A.13, a parse-level duplicate-key
 * case, is covered by JsonText's strict parsing instead).
 */
class JsonPatchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static JsonNode apply(String doc, String patch) {
        return JsonPatch.apply(json(patch), json(doc));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            A.1 add object member         | {"foo":"bar"}                                         | [{"op":"add","path":"/baz","value":"qux"}]                     | {"baz":"qux","foo":"bar"}
            A.2 add array element         | {"foo":["bar","baz"]}                                 | [{"op":"add","path":"/foo/1","value":"qux"}]                   | {"foo":["bar","qux","baz"]}
            A.3 remove object member      | {"baz":"qux","foo":"bar"}                             | [{"op":"remove","path":"/baz"}]                                 | {"foo":"bar"}
            A.4 remove array element      | {"foo":["bar","qux","baz"]}                           | [{"op":"remove","path":"/foo/1"}]                               | {"foo":["bar","baz"]}
            A.5 replace value             | {"baz":"qux","foo":"bar"}                             | [{"op":"replace","path":"/baz","value":"boo"}]                 | {"baz":"boo","foo":"bar"}
            A.6 move value                | {"foo":{"bar":"baz","waldo":"fred"},"qux":{"corge":"grault"}} | [{"op":"move","from":"/foo/waldo","path":"/qux/thud"}] | {"foo":{"bar":"baz"},"qux":{"corge":"grault","thud":"fred"}}
            A.7 move array element        | {"foo":["all","grass","cows","eat"]}                  | [{"op":"move","from":"/foo/1","path":"/foo/3"}]                 | {"foo":["all","cows","eat","grass"]}
            A.8 test succeeds             | {"baz":"qux","foo":["a",2,"c"]}                       | [{"op":"test","path":"/baz","value":"qux"},{"op":"test","path":"/foo/1","value":2}] | {"baz":"qux","foo":["a",2,"c"]}
            A.10 add nested object        | {"foo":"bar"}                                         | [{"op":"add","path":"/child","value":{"grandchild":{}}}]        | {"foo":"bar","child":{"grandchild":{}}}
            A.11 unknown members ignored  | {"foo":"bar"}                                         | [{"op":"add","path":"/baz","value":"qux","xyz":123}]            | {"foo":"bar","baz":"qux"}
            A.14 ~ escape ordering        | {"/":9,"~1":10}                                       | [{"op":"test","path":"/~01","value":10}]                        | {"/":9,"~1":10}
            A.16 add array value          | {"foo":["bar"]}                                       | [{"op":"add","path":"/foo/-","value":["abc","def"]}]            | {"foo":["bar",["abc","def"]]}
            copy                          | {"a":{"b":1}}                                         | [{"op":"copy","from":"/a","path":"/c"}]                         | {"a":{"b":1},"c":{"b":1}}
            add at root replaces document | {"a":1}                                               | [{"op":"add","path":"","value":{"b":2}}]                        | {"b":2}
            replace root                  | {"a":1}                                               | [{"op":"replace","path":"","value":[1,2]}]                      | [1,2]
            numbers equal by value        | {"n":1}                                               | [{"op":"test","path":"/n","value":1.0}]                         | {"n":1}
            """)
    void rfcExamples(String name, String doc, String patch, String expected) {
        assertThat(apply(doc, patch)).isEqualTo(json(expected));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            A.9 test fails                | {"baz":"qux"}         | [{"op":"test","path":"/baz","value":"bar"}]       | expected "bar" but found "qux"
            A.12 add to missing parent    | {"foo":"bar"}         | [{"op":"add","path":"/baz/bat","value":"qux"}]    | /baz does not exist
            A.15 string is not number     | {"/":9,"~1":10}       | [{"op":"test","path":"/~01","value":"10"}]        | expected "10" but found 10
            remove missing member         | {"a":1}               | [{"op":"remove","path":"/b"}]                      | /b does not exist
            index past end                | {"a":[1]}             | [{"op":"add","path":"/a/2","value":0}]             | out of range
            leading zero index            | {"a":[1,2]}           | [{"op":"add","path":"/a/01","value":0}]            | out of range
            move into own child           | {"a":{"b":1}}         | [{"op":"move","from":"/a","path":"/a/b/c"}]        | own children
            unknown op                    | {}                    | [{"op":"merge","path":"/a","value":1}]             | unknown op 'merge'
            missing value                 | {}                    | [{"op":"add","path":"/a"}]                          | needs a "value"
            bad pointer                   | {}                    | [{"op":"add","path":"a","value":1}]                 | must be empty or start with '/'
            not an array                  | {}                    | {"op":"add","path":"/a","value":1}                  | must be an array
            remove root                   | {}                    | [{"op":"remove","path":""}]                         | cannot remove the whole document
            """)
    void errorsExplainWhatFailed(String name, String doc, String patch, String expectedMessage) {
        assertThatThrownBy(() -> apply(doc, patch))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    void patchesAreAtomicAndNeverModifyTheInput() {
        JsonNode doc = json("{\"a\":1}");

        assertThatThrownBy(() -> JsonPatch.apply(json("""
                [{"op":"add","path":"/b","value":2},{"op":"test","path":"/a","value":99}]"""), doc))
                .hasMessageContaining("Operation 2");
        assertThat(doc).isEqualTo(json("{\"a\":1}"));
    }

    @Test
    void pointerParsingAndEscaping() {
        assertThat(JsonPointer.parse("").isRoot()).isTrue();
        assertThat(JsonPointer.parse("/a~1b/m~0n").toString()).isEqualTo("/a~1b/m~0n");
        assertThat(JsonPointer.parse("/a~1b/m~0n").resolve(json("{\"a/b\":{\"m~n\":7}}")).asInt()).isEqualTo(7);
        assertThat(JsonPointer.parse("/a").isProperPrefixOf(JsonPointer.parse("/a/b"))).isTrue();
        assertThat(JsonPointer.parse("/a").isProperPrefixOf(JsonPointer.parse("/a"))).isFalse();
        assertThatThrownBy(() -> JsonPointer.parse("/bad~2escape")).hasMessageContaining("Invalid escape");
    }
}
