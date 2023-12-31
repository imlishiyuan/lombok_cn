//version 8: Jackson deps are at least Java7+.
public class JacksonizedSuperBuilderSimple {
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
	@com.fasterxml.jackson.databind.annotation.JsonDeserialize(builder = JacksonizedSuperBuilderSimple.Parent.ParentBuilderImpl.class)
	public static class Parent {
		int field1;
		@java.lang.SuppressWarnings("all")
		public static abstract class ParentBuilder<C extends JacksonizedSuperBuilderSimple.Parent, B extends JacksonizedSuperBuilderSimple.Parent.ParentBuilder<C, B>> {
			@java.lang.SuppressWarnings("all")
			private int field1;
			/**
			 * @return {@code this}.
			 */
			@java.lang.SuppressWarnings("all")
			public B field1(final int field1) {
				this.field1 = field1;
				return self();
			}
			@java.lang.SuppressWarnings("all")
			protected abstract B self();
			@java.lang.SuppressWarnings("all")
			public abstract C build();
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public java.lang.String toString() {
				return "JacksonizedSuperBuilderSimple.Parent.ParentBuilder(field1=" + this.field1 + ")";
			}
		}
		@java.lang.SuppressWarnings("all")
		@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
		@com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
		static final class ParentBuilderImpl extends JacksonizedSuperBuilderSimple.Parent.ParentBuilder<JacksonizedSuperBuilderSimple.Parent, JacksonizedSuperBuilderSimple.Parent.ParentBuilderImpl> {
			@java.lang.SuppressWarnings("all")
			private ParentBuilderImpl() {
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			protected JacksonizedSuperBuilderSimple.Parent.ParentBuilderImpl self() {
				return this;
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public JacksonizedSuperBuilderSimple.Parent build() {
				return new JacksonizedSuperBuilderSimple.Parent(this);
			}
		}
		@java.lang.SuppressWarnings("all")
		protected Parent(final JacksonizedSuperBuilderSimple.Parent.ParentBuilder<?, ?> b) {
			this.field1 = b.field1;
		}
		@java.lang.SuppressWarnings("all")
		public static JacksonizedSuperBuilderSimple.Parent.ParentBuilder<?, ?> builder() {
			return new JacksonizedSuperBuilderSimple.Parent.ParentBuilderImpl();
		}
	}
	public static void test() {
		Parent x = Parent.builder().field1(5).build();
	}
}
