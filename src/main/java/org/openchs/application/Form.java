package org.openchs.application;

import org.openchs.domain.CHSEntity;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "form")
public class Form extends CHSEntity {
    @NotNull
    @Enumerated(EnumType.STRING)
    private FormType formType;

    @NotNull
    private String name;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "form")
    private Set<FormElementGroup> formElementGroups;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<FormElementGroup> getFormElementGroups() {
        return formElementGroups;
    }

    public void setFormElementGroups(Set<FormElementGroup> formElementGroups) {
        this.formElementGroups = formElementGroups;
    }

    public FormType getFormType() {
        return formType;
    }

    public void setFormType(FormType formType) {
        this.formType = formType;
    }

    public static Form create() {
        Form form = new Form();
        form.formElementGroups = new HashSet<>();
        return form;
    }

    public FormElementGroup getFormElementGroup(String formElementGroupName) {
        for (FormElementGroup formElementGroup : formElementGroups) {
            if (formElementGroup.getName().equals(formElementGroupName)) return formElementGroup;
        }
        return null;
    }

    public void clearGroups() {
        formElementGroups.clear();
    }

    public FormElementGroup addFormElementGroup(String formElementGroupUUID) {
        FormElementGroup formElementGroup = FormElementGroup.create();
        this.formElementGroups.add(formElementGroup);
        formElementGroup.setForm(this);
        formElementGroup.setUuid(formElementGroupUUID);
        return formElementGroup;
    }

    public FormElementGroup findFormElementGroup(String uuid) {
        return formElementGroups.stream().filter(x -> x.getUuid().equals(uuid)).findAny().orElse(null);
    }

    public void removeFormElementGroups(List<String> formElementGroupUUIDs) {
        List<FormElementGroup> orphanedFormElementGroups = getFormElementGroups().stream().filter(formElementGroup -> !formElementGroupUUIDs.contains(formElementGroup.getUuid())).collect(Collectors.toList());
        formElementGroups.removeAll(orphanedFormElementGroups);
    }
}