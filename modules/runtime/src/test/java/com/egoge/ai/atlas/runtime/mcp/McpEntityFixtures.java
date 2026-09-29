/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The tool beans of {@code McpEntityResultTest} (issue #50): tools in the shape the processor
 * generates for a method with no resolvable {@code returnType}, which return the raw entity, and
 * for shapes it does not check.
 */
final class McpEntityFixtures {

    static final String SSN = "123-45-6789";

    private McpEntityFixtures() {
    }

    /** The real application: every auto-configuration on the test class path, no component scan. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PersonMcpTool.class, EnvelopeController.class})
    static class EntityToolApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PersonMcpTool.class)
    static class ToolBeans {
    }

    @AgenticEntity
    public static class Person {

        @AgenticField
        private Long id;

        @AgenticField
        private String name;

        // NOT annotated: must never reach a client
        private String ssn;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getSsn() { return ssn; }
        public void setSsn(String ssn) { this.ssn = ssn; }
    }

    /** An unannotated subtype: none of its own getters may reach a client. */
    public static class Vip extends Person {

        @AgenticField
        private String tier;

        private String agent;

        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }

        public String getAgent() { return agent; }
        public void setAgent(String agent) { this.agent = agent; }
    }

    /** A generated DTO whose component is an unannotated subtype, as the processor emits it with projections off. */
    public record EnvelopeDto(String label, Vip person) {
    }

    /** A generated DTO: its JSON must not change. */
    public record PersonDto(Long id, String name, LocalDate born, String nickname, List<String> tags) {
    }

    static Person person() {
        Person person = new Person();
        person.setId(1L);
        person.setName("Ada");
        person.setSsn(SSN);
        return person;
    }

    static Vip vip() {
        Vip vip = new Vip();
        vip.setId(2L);
        vip.setName("Grace");
        vip.setSsn(SSN);
        vip.setTier("gold");
        vip.setAgent("Bond");
        return vip;
    }

    static PersonDto dto() {
        return new PersonDto(3L, "Linus", LocalDate.of(1969, 12, 28), null, List.of("a", "b"));
    }

    static EnvelopeDto envelope() {
        return new EnvelopeDto("vip", vip());
    }

    /** In the shape of a generated MCP tool class. */
    @Service
    public static class PersonMcpTool {

        @Tool(name = "get_person", description = "get_person")
        public Person getPerson(@ToolParam(description = "id") Long id) {
            return person();
        }

        @Tool(name = "list_people", description = "list_people")
        public List<Person> listPeople() {
            return List.of(person(), vip());
        }

        @Tool(name = "people_by_key", description = "people_by_key")
        public Map<String, Person> peopleByKey() {
            return Map.of("ada", person());
        }

        @Tool(name = "stream_people", description = "stream_people")
        public Stream<Person> streamPeople() {
            return Stream.of(person());
        }

        @Tool(name = "nested_people", description = "nested_people")
        public List<List<Person>> nestedPeople() {
            return List.of(List.of(person()));
        }

        @Tool(name = "people_array", description = "people_array")
        public Person[] peopleArray() {
            return new Person[] {person()};
        }

        @Tool(name = "super_people", description = "super_people")
        public Iterable<? super Person> superPeople() {
            Collection<Object> people = new ArrayList<>();
            people.add(person());
            return people;
        }

        @Tool(name = "get_vip", description = "get_vip")
        public Vip getVip() {
            return vip();
        }

        @Tool(name = "get_envelope", description = "get_envelope")
        public EnvelopeDto getEnvelope() {
            return envelope();
        }

        @Tool(name = "get_dto", description = "get_dto")
        public PersonDto getDto() {
            return dto();
        }

        @Tool(name = "greet", description = "greet")
        public String greet(@ToolParam(description = "name") String name) {
            return "hello " + name;
        }

        @Tool(name = "forget", description = "forget")
        public void forget(@ToolParam(description = "id") Long id) {
        }

        @Tool(name = "own_converter", description = "own_converter", resultConverter = FixedConverter.class)
        public Person ownConverter() {
            return person();
        }
    }

    /** A result converter a tool names itself: AI-ATLAS keeps it. */
    public static class FixedConverter implements ToolCallResultConverter {

        static final String RESULT = "\"converted by the tool's own converter\"";

        @Override
        public String convert(Object result, Type returnType) {
            return RESULT;
        }
    }

    /** A tool the application registers through its own provider, opted in to the whitelist. */
    public static class OptedInTool {

        @Tool(name = "opted_in", description = "opted_in", resultConverter = AgentSafeToolCallResultConverter.class)
        public Vip optedIn() {
            return vip();
        }
    }

    /** The REST channel for the same DTO. */
    @RestController
    public static class EnvelopeController {

        @GetMapping("/envelope")
        public EnvelopeDto envelope() {
            return McpEntityFixtures.envelope();
        }
    }
}
